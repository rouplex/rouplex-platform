package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.commons.builders.SingleInstanceBuilder;
import org.rouplex.commons.utils.ValidationUtils;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The central place for managing tcp endpoints such as {@link TcpClient} and {@link TcpServer} instances.
 *
 * A configurable number of {@link TcpSelector}s and corresponding {@link Thread}s are used internally, each performing
 * channel selections on a subset of the endpoints for maximum use of processing resources.
 *
 * The suggested usage is to create just one instance of this class, then subsequently create all tcp clients or
 * servers using that instance. In more advanced use-cases, more than one instance of this class may be preferred (as
 * in compartmentalizing resources).
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */

public class TcpReactor implements Closeable {
    public static class Builder extends TcpReactorBuilder<TcpReactor, Builder> {
        /**
         * Build the reactor and return it.
         *
         * @return
         *          The built reactor
         * @throws
         *          Exception if any problems arise during the reactor creation and connection initialization
         */
        @Override
        public TcpReactor build() throws Exception {
            return buildTcpReactor();
        }
    }

    /**
     * A {@link TcpReactor} builder. The builder can only build one reactor, and once done, any future calls to alter 
     * the builder or try to rebuild will fail with {@link IllegalStateException}.
     *
     * Not thread safe.
     */
    protected abstract static class TcpReactorBuilder<T, B extends TcpReactorBuilder> extends SingleInstanceBuilder<T, B> {
        protected SelectorProvider selectorProvider;
        protected ThreadFactory threadFactory;
        protected int threadCount;
        protected Executor eventsExecutor;
        protected boolean userSetEventsExecutor;

        public B withSelectorProvider(SelectorProvider selectorProvider) {
            checkNotBuilt();

            this.selectorProvider = selectorProvider;
            return builder;
        }

        public B withThreadFactory(ThreadFactory threadFactory) {
            checkNotBuilt();

            this.threadFactory = threadFactory;
            return builder;
        }

        public B withThreadCount(int threadCount) {
            checkNotBuilt();
            ValidationUtils.checkedNotNegative(threadCount, "threadCount");

            this.threadCount = threadCount;
            return builder;
        }

        public B withEventsExecutor(Executor eventsExecutor) {
            // if set to null, then events will be fired from the selection threads
            checkNotBuilt();

            userSetEventsExecutor = true;
            this.eventsExecutor = eventsExecutor;
            return builder;
        }

        @Override
        protected void checkCanBuild() {
        }

        @Override
        protected void prepareBuild() throws Exception {
            super.prepareBuild();

            if (selectorProvider == null) {
                selectorProvider = SelectorProvider.provider();
            }

            if (threadFactory == null) {
                threadFactory = Executors.defaultThreadFactory();
            }

            if (threadCount == 0) {
                threadCount = Runtime.getRuntime().availableProcessors();
            }

            if (eventsExecutor == null && !userSetEventsExecutor) {
                eventsExecutor = Executors.newCachedThreadPool();
            }
        }

        protected TcpReactor buildTcpReactor() throws Exception {
            prepareBuild();
            return new TcpReactor(this);
        }
    }

    private final TcpSelector[] tcpSelectors;
    private final Set<Thread> tcpSelectorThreads = new HashSet<Thread>();
    private final AtomicInteger tcpSelectorIndex = new AtomicInteger();
    protected final Executor eventsExecutor;
    protected final boolean userSetEventsExecutor;

    public TcpReactor(TcpReactorBuilder builder) throws IOException {
        tcpSelectors = new TcpSelector[builder.threadCount];
        eventsExecutor = builder.eventsExecutor;
        userSetEventsExecutor = builder.userSetEventsExecutor;

        for (int index = 0; index < tcpSelectors.length; index++) {
            TcpSelector tcpSelector = new TcpSelector(builder.selectorProvider.openSelector(),
                builder.threadFactory, "TcpReactor-" + hashCode() + "-TcpSelector-" + index);

            tcpSelectorThreads.add(tcpSelector.tcpSelectorThread);
            tcpSelectors[index] = tcpSelector;
        }
    }

    /**
     * We assign each created channel to the next {@link TcpSelector} in a round-robin fashion.
     * TODO: Improve nextTcpSelector to pick the selector with less SelectionKeys
     *
     * @return
     *          The next TcpSelector to be used
     */
    TcpSelector nextTcpSelector() {
        return tcpSelectors[tcpSelectorIndex.getAndIncrement() % tcpSelectors.length];
    }

    @Override
    public void close() {
        for (TcpSelector tcpSelector : tcpSelectors) {
            tcpSelector.requestClose(null);
        }
    }

    /**
     * Internal class, not to be accessed directly by the user. It performs tasks related to registering and unregistering
     * channels with the nio selector that it owns, selecting on those channels and forwarding read-ready and write-ready
     * notifications towards the respective channels for user processing.
     *
     * The number of instances of this class will be usually related to the number of the CPU's available in the host, but
     * it is controlled indirectly by threadCount argument in one of {@link TcpReactor} constructors.
     *
     * @author Andi Mullaraj (andimullaraj at gmail.com)
     */
    class TcpSelector implements Runnable {
        private final Object lock = new Object();
        final Selector selector;
        final Thread tcpSelectorThread;

        @GuardedBy("lock")
        private List<TcpEndPoint> asyncRegisterTcpEndPoints = new ArrayList<TcpEndPoint>();
        @GuardedBy("lock")
        private Map<TcpEndPoint, Exception> asyncUnregisterTcpEndPoints = new HashMap<TcpEndPoint, Exception>();
        @GuardedBy("lock")
        private List<TcpClient> asyncAddTcpReadClients = new ArrayList<TcpClient>();
        @GuardedBy("lock")
        private List<Runnable> asyncAddTcpReadCallbacks = new ArrayList<Runnable>();
        @GuardedBy("lock")
        private List<TcpClient> asyncAddTcpWriteClients = new ArrayList<TcpClient>();
        @GuardedBy("lock")
        private List<Runnable> asyncAddTcpWriteCallbacks = new ArrayList<Runnable>();

        @GuardedBy("not-needed: always accessed from same thread")
        protected Set<TcpClient> updatedTcpClients = new HashSet<TcpClient>();

        @GuardedBy("lock") // Most likely will go away
        private List<PendingOps> pausingInterestOps = new ArrayList<PendingOps>();
        @GuardedBy("lock") // Most likely will go away
        private List<PendingOps> resumingInterestOps = new ArrayList<PendingOps>();
        // Most likely will go away
        private final SortedMap<Long, List<PendingOps>> resumingLaterInterestOps = new TreeMap<Long, List<PendingOps>>();

        @GuardedBy("lock") private boolean closed;
        private Exception fatalException;

        private class PendingOps {
            final SelectionKey selectionKey;
            final int pendingOps;
            final long timestamp;

            PendingOps(SelectionKey selectionKey, int pendingOps, long timestamp) {
                this.selectionKey = selectionKey;
                this.pendingOps = pendingOps;
                this.timestamp = timestamp;
            }

            void disablePendingOps() {
                selectionKey.interestOps(selectionKey.interestOps() & ~pendingOps);
            }

            void enablePendingOps() {
                selectionKey.interestOps(selectionKey.interestOps() | pendingOps);
            }
        }

        TcpSelector(Selector selector, ThreadFactory threadFactory, String threadName) {
            this.selector = selector;

            tcpSelectorThread = threadFactory.newThread(this);
            tcpSelectorThread.setDaemon(true);
            tcpSelectorThread.setName(threadName);
            tcpSelectorThread.start();
        }

        Set<Thread> getTcpSelectorThreads() {
            return tcpSelectorThreads;
        }
        
        /**
         * Add the endpoint to be registered on next cycle of selector. We merely keep a reference on it, and wakeup the
         * background tcpSelectorThread which is tasked with selecting as well as registering new / unregistering old 
         * endpoints.
         *
         * @param tcpEndPoint
         *          The endpoint to be added for selection inside the main loop
         * @throws
         *          IOException if the TcpReactor has already been closed
         */
        void asyncRegisterTcpEndPoint(TcpEndPoint tcpEndPoint) throws IOException {
            synchronized (lock) {
                if (closed) {
                    throw new IOException("TcpReactor is already closed.");
                }

                asyncRegisterTcpEndPoints.add(tcpEndPoint);
            }

            selector.wakeup();
        }

        void asyncAddTcpReadChannel(TcpClient tcpClient, Runnable channelReadyCallback) throws IOException {
            synchronized (lock) {
                if (closed) {
                    throw new IOException("Cannot add ReadChannel. Cause: TcpReactor is already closed.");
                }

                asyncAddTcpReadClients.add(tcpClient);
                asyncAddTcpReadCallbacks.add(channelReadyCallback);
            }

            selector.wakeup();
        }

        void asyncAddTcpWriteChannel(TcpClient tcpClient, Runnable channelReadyCallback) throws IOException {
            synchronized (lock) {
                if (closed) {
                    throw new IOException("Cannot add WriteChannel. Cause: TcpReactor is already closed.");
                }

                asyncAddTcpWriteClients.add(tcpClient);
                asyncAddTcpWriteCallbacks.add(channelReadyCallback);
            }

            selector.wakeup();
        }

        /**
         * For channels that closed and need to report via selector. Add the tcpEndPoint to be unregistered on next cycle of
         * selector.
         *
         * @param tcpEndPoint
         *          The endpoint that needs to be unregistered
         * @param optionalReason
         *          If there was an exception which resulted in the endpoint being closed, null otherwise
         */
        void asyncUnregisterTcpEndPoint(TcpEndPoint tcpEndPoint, Exception optionalReason) throws IOException {
            synchronized (lock) {
                if (closed) {
                    throw new IOException("Cannot unregister EndPoint. Cause: TcpReactor is already closed.");
                }

                asyncUnregisterTcpEndPoints.put(tcpEndPoint, optionalReason);
            }

            selector.wakeup();
        }

        private boolean processAccumulatedAsyncRequests() {
            List<TcpEndPoint> registerTcpEndPoints;
            Map<TcpEndPoint, Exception> unregisterTcpEndPoints;

            List<TcpClient> addTcpReadClients;
            List<Runnable> addTcpReadCallbacks;
            List<TcpClient> addTcpWriteClients;
            List<Runnable> addTcpWriteCallbacks;

            synchronized (lock) {
                if (closed) {
                    return false;
                }

                if (asyncRegisterTcpEndPoints.isEmpty()) {
                    registerTcpEndPoints = null;
                } else {
                    registerTcpEndPoints = asyncRegisterTcpEndPoints;
                    asyncRegisterTcpEndPoints = new ArrayList<TcpEndPoint>();
                }

                if (asyncAddTcpReadClients.isEmpty()) {
                    addTcpReadClients = null;
                    addTcpReadCallbacks = null;
                } else {
                    addTcpReadClients = asyncAddTcpReadClients;
                    addTcpReadCallbacks = asyncAddTcpReadCallbacks;
                    asyncAddTcpReadClients = new ArrayList<TcpClient>();
                    asyncAddTcpReadCallbacks = new ArrayList<Runnable>();
                }

                if (asyncAddTcpWriteClients.isEmpty()) {
                    addTcpWriteClients = null;
                    addTcpWriteCallbacks = null;
                } else {
                    addTcpWriteClients = asyncAddTcpWriteClients;
                    addTcpWriteCallbacks = asyncAddTcpWriteCallbacks;
                    asyncAddTcpWriteClients = new ArrayList<TcpClient>();
                    asyncAddTcpWriteCallbacks = new ArrayList<Runnable>();
                }

                if (asyncUnregisterTcpEndPoints.isEmpty()) {
                    unregisterTcpEndPoints = null;
                } else {
                    unregisterTcpEndPoints = asyncUnregisterTcpEndPoints;
                    asyncUnregisterTcpEndPoints = new HashMap<TcpEndPoint, Exception>();
                }

                if (!pausingInterestOps.isEmpty()) {
                    for (PendingOps pausingOps : pausingInterestOps) {
                        pauseInterestOps(pausingOps);
                    }

                    pausingInterestOps = new ArrayList<PendingOps>();
                }

                if (!resumingInterestOps.isEmpty()) {
                    for (PendingOps resumingOps : resumingInterestOps) {
                        try {
                            resumingOps.enablePendingOps();
                        } catch (CancelledKeyException cke) {
                            // key will be removed from selector on next select
                        }
                    }

                    resumingInterestOps = new ArrayList<PendingOps>();
                }
            }

            // Fire only lock-free events towards client! In this particular case, only "connected" event
            // (when TcpClient was accepted via TcpServer) can be fired, and without any side effects
            if (registerTcpEndPoints != null) {
                for (TcpEndPoint tcpEndPoint : registerTcpEndPoints) {
                    tcpEndPoint.syncHandleRegistration();
                }
            }

            if (addTcpReadClients != null) {
                int index = 0;
                for (TcpClient tcpClient : addTcpReadClients) {
                    tcpClient.tcpReadChannel.syncAddChannelReadyCallback(addTcpReadCallbacks.get(index++));
                }
            }

            // fire only lock-free events towards client! Same as ReadReady scenario.
            if (addTcpWriteClients != null) {
                int index = 0;
                for (TcpClient tcpClient : addTcpWriteClients) {
                    tcpClient.tcpWriteChannel.syncAddChannelReadyCallback(addTcpWriteCallbacks.get(index++));
                }
            }

            if (unregisterTcpEndPoints != null) {
                for (Map.Entry<TcpEndPoint, Exception> tcpEndPoint : unregisterTcpEndPoints.entrySet()) {
                    tcpEndPoint.getKey().syncHandleUnregistration(tcpEndPoint.getValue());
                }
            }

            return true;
        }

        @Override
        public void run() {
            long timeInsideSelectNano = 0;
            long timeOutsideSelectNano = 0;
            Set<SelectionKey> selectedKeys = new HashSet<SelectionKey>();
            
            try {
                long startSelectionNano = System.nanoTime();
                long endSelectionNano = startSelectionNano;

                while (true) {
                    long now = System.currentTimeMillis();
                    if (!processAccumulatedAsyncRequests()) {
                        break; // closed
                    }

                    long selectTimeout = 0;
                    for (Iterator<Map.Entry<Long, List<PendingOps>>> iterator
                         = resumingLaterInterestOps.entrySet().iterator(); iterator.hasNext(); ) {
                        Map.Entry<Long, List<PendingOps>> resumingOpsAtSameTime = iterator.next();

                        if (resumingOpsAtSameTime.getKey() > now) {
                            selectTimeout = resumingOpsAtSameTime.getKey() - now;
                            break;
                        }

                        for (PendingOps resumingOps : resumingOpsAtSameTime.getValue()) {
                            try {
                                resumingOps.enablePendingOps();
                            } catch (CancelledKeyException e) {
                                // key will be removed from selector on next select
                            }
                        }

                        iterator.remove();
                    }

                    // Debug: tcpReactor.tcpMetrics.syncAddTcpRWClientsCount.inc(syncUpdateTcpClients.size());
                    // clients growing their interestOps
                    for (TcpClient tcpClient : updatedTcpClients) {
                        tcpClient.syncUpdateInterestOps();
                        selectedKeys.remove(tcpClient.selectionKey);
                    }

                    updatedTcpClients.clear();

                    // rest of the clients (shrinking their interestOps)
                    for (Iterator<SelectionKey> selectionKeyIter = selectedKeys.iterator(); selectionKeyIter.hasNext();) {
                        ((TcpClient) selectionKeyIter.next().attachment()).syncUpdateInterestOps();
                        selectionKeyIter.remove();
                    }

                    startSelectionNano = System.nanoTime();
                    timeOutsideSelectNano += startSelectionNano - endSelectionNano;
                    // Debug: tcpReactor.tcpMetrics.timeOutsideSelectNano.update(startSelectionNano - endSelectionNano, TimeUnit.NANOSECONDS);

                    int updated = selector.select(selectTimeout);
                    selectedKeys = selector.selectedKeys();

                    endSelectionNano = System.nanoTime();
                    timeInsideSelectNano += endSelectionNano - startSelectionNano;

                    // Debug: tcpReactor.tcpMetrics.timeInsideSelectNano.update(endSelectionNano - startSelectionNano, TimeUnit.NANOSECONDS);
                    // Debug: tcpReactor.tcpMetrics.selectedKeysBuckets.update(updated);

                    for (Iterator<SelectionKey> selectedKeysIter = selectedKeys.iterator(); selectedKeysIter.hasNext();) {
                        SelectionKey selectionKey = selectedKeysIter.next();

                        if (selectionKey.isAcceptable()) {
                            try {
                                selectedKeysIter.remove();

                                SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
                                socketChannel.configureBlocking(false);
                                TcpSelector tcpSelector = nextTcpSelector();

                                // asyncRegisterTcpEndPoint (and not registerTcpEndPoint) b/c tcpSelector is different
                                tcpSelector.asyncRegisterTcpEndPoint(
                                    new TcpClient(socketChannel, tcpSelector, (TcpServer) selectionKey.attachment()));
                            } catch (Exception e) {
                                // e.printStackTrace();
                                // server failed to accept connection ... this should be surfaced for ddos scenarios
                            }
                            continue;
                        }

                        ((TcpClient) selectionKey.attachment()).syncHandleOpsReady();
                    }
                }
            } catch (Exception e) {
                handleSelectException(e);
            }

            syncClose(); // close synchronously.
        }

        // Most likely will go away
        private void pauseInterestOps(PendingOps pausingOps) {
            try {
                pausingOps.disablePendingOps();

                if (pausingOps.timestamp != 0) {
                    List<PendingOps> resumingOps = resumingLaterInterestOps.get(pausingOps.timestamp);
                    if (resumingOps == null) {
                        resumingOps = new ArrayList<PendingOps>();
                        resumingLaterInterestOps.put(pausingOps.timestamp, resumingOps);
                    }

                    resumingOps.add(pausingOps);
                }
            } catch (CancelledKeyException cke) {
                // key will be removed from selector on next select
            }
        }

        /**
         * Remove interest ops for the selection key and add them back later (or never). This call will queue the request
         * to remove the interestOps then wakeup the selector so that the service tcpSelectorThread can perform the update
         * synchronously on next cycle; the selector will be instructed to select for the remainder of the time (or less),
         * and will be adding the interest ops back, then remove the entry from the queue.
         *
         * @param selectionKey the key for which we need the new interest ops
         */
        // Most likely will go away
        void asyncPauseInterestOps(SelectionKey selectionKey, int interestOps, long resumeTimestamp) {
            synchronized (lock) {
                try {
                    pausingInterestOps.add(new PendingOps(selectionKey, interestOps, resumeTimestamp));
                } catch (Exception e) {
                    // TcpServer may have been built using an existing socketChannel, so its owner could close it
                    // at any time, hence cancelling this selectionKey -- we will just ignore this, since the key will be
                    // unregistered with this channel in the next select() round
                }
            }
        }

        /**
         * Remove interest ops for the selection key and add them back later (or never). This call will queue the request
         * to remove the interestOps then wakeup the selector so that the service tcpSelectorThread can perform the update
         * synchronously on next cycle; the selector will be instructed to select for the remainder of the time (or less),
         * and will be adding the interest ops back, then remove the entry from the queue.
         *
         * @param selectionKey the key for which we need the new interest ops
         */
        // Most likely will go away
        void asyncResumeInterestOps(SelectionKey selectionKey, int interestOps) {
            synchronized (lock) {
                try {
                    resumingInterestOps.add(new PendingOps(selectionKey, interestOps, 0));
                } catch (Exception e) {
                    // TcpServer may have been built using an existing socketChannel, so its owner could close it
                    // at any time, hence cancelling this selectionKey -- we will just ignore this, since the key will be
                    // unregistered with this channel in the next select() round
                }
            }

            selector.wakeup();
        }

        void requestClose(@Nullable Exception optionalException) {
            synchronized (lock) {
                if (closed) {
                    return;
                }

                closed = true;
                fatalException = optionalException;
            }

            selector.wakeup();
        }

        void handleSelectedKeyException(Exception e) {
            // AOP wraps this call when debugging
        }

        void handleSelectException(Exception e) {
            // AOP wraps this call when debugging and the argument e provides useful info
            close(); // bubble up the fatal exception by asking the broker to close
        }

        /**
         * The component is already in closed state, with no way to pass any exceptions to its user. That's why we silence
         * all exceptions here. Always called by the same tcpSelectorThread, servicing this instance.
         */
        private void syncClose() {
            for (SelectionKey selectionKey : selector.keys()) {
                ((TcpEndPoint) selectionKey.attachment()).syncHandleUnregistration(fatalException);
            }

            for (TcpEndPoint tcpEndPoint : asyncRegisterTcpEndPoints) {
                tcpEndPoint.syncHandleUnregistration(fatalException);
            }

            try {
                selector.close();
            } catch (IOException ioe) {
                // Nothing to be done here
            }

            try {
                if (!userSetEventsExecutor) {
                    // then eventsExecutor is non-null by default
                    ((ExecutorService) eventsExecutor).shutdownNow();
                }
            } catch (Exception e) {
                // Nothing to be done here
            }
        }
    }
}
