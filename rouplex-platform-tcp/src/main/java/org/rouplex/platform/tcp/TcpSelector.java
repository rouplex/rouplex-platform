package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private static final Logger logger = Logger.getLogger(TcpSelector.class.getSimpleName());
    private final Object lock = new Object();

    final TcpReactor tcpReactor;
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

    @GuardedBy("no-need-guarding")
    final Set<TcpClient> syncAddTcpRWClients = new HashSet<>();

    @GuardedBy("lock") // Most likely will go away
    private List<PendingOps> pausingInterestOps = new ArrayList<PendingOps>();
    @GuardedBy("lock") // Most likely will go away
    private List<PendingOps> resumingInterestOps = new ArrayList<PendingOps>();
    // Most likely will go away
    private final SortedMap<Long, List<PendingOps>> resumingLaterInterestOps = new TreeMap<Long, List<PendingOps>>();

    private boolean closed;
    private Exception fatalException;

    // TODO better via a a (linked) array
    List<TcpClient> tcpClientsUpdated = new ArrayList<>();

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

    TcpSelector(TcpReactor tcpReactor, Selector selector, ThreadFactory threadFactory, String threadName) {
        this.tcpReactor = tcpReactor;
        this.selector = selector;

        tcpSelectorThread = threadFactory.newThread(this);
        tcpSelectorThread.setDaemon(true);
        tcpSelectorThread.setName(threadName);
        tcpSelectorThread.start();
    }

    /**
     * Add the endpoint to be registered on next cycle of selector. We merely keep a reference on it, and wakeup the
     * background tcpSelectorThread which is tasked with selecting as well as registering new / unregistering old endpoints.
     *
     * @param tcpEndPoint
     *          The endpoint to be added for selection inside the main loop
     * @throws
     *          IOException if the instance has already been closed
     */
    void asyncRegisterTcpEndPoint(TcpEndPoint tcpEndPoint) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("TcpReactor already closed.");
            }

            asyncRegisterTcpEndPoints.add(tcpEndPoint);
        }

        selector.wakeup();
    }

    void asyncAddTcpReadChannel(TcpClient tcpClient, Runnable channelReadyCallback) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("TcpReactor already closed.");
            }

            asyncAddTcpReadClients.add(tcpClient);
            asyncAddTcpReadCallbacks.add(channelReadyCallback);
        }

        selector.wakeup();
    }

    void asyncAddTcpWriteChannel(TcpClient tcpClient, Runnable channelReadyCallback) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("TcpReactor already closed.");
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
                throw new IOException("TcpReactor already closed.");
            }

            asyncUnregisterTcpEndPoints.put(tcpEndPoint, optionalReason);
        }

        selector.wakeup();
    }

    boolean processAccumulatedAsyncRequests() {
        List<TcpEndPoint> registerTcpEndPoints = new ArrayList<TcpEndPoint>();
        Map<TcpEndPoint, Exception> unregisterTcpEndPoints = new HashMap<TcpEndPoint, Exception>();

        List<TcpClient> addTcpReadClients;
        List<Runnable> addTcpReadCallbacks = null;
        List<TcpClient> addTcpWriteClients;
        List<Runnable> addTcpWriteCallbacks = null;

        synchronized (lock) {
            if (closed) {
                return false;
            }

            if (!asyncRegisterTcpEndPoints.isEmpty()) {
                registerTcpEndPoints = asyncRegisterTcpEndPoints;
                asyncRegisterTcpEndPoints = new ArrayList<TcpEndPoint>();
            } else {
                registerTcpEndPoints = null;
            }

            if (!asyncAddTcpReadClients.isEmpty()) {
                addTcpReadClients = asyncAddTcpReadClients;
                asyncAddTcpReadClients = new ArrayList<TcpClient>();
                addTcpReadCallbacks = asyncAddTcpReadCallbacks;
                asyncAddTcpReadCallbacks = new ArrayList<Runnable>();
            } else {
                addTcpReadClients = null;
            }

            if (!asyncAddTcpWriteClients.isEmpty()) {
                addTcpWriteClients = asyncAddTcpWriteClients;
                asyncAddTcpWriteClients = new ArrayList<TcpClient>();
                addTcpWriteCallbacks = asyncAddTcpWriteCallbacks;
                asyncAddTcpWriteCallbacks = new ArrayList<Runnable>();
            } else {
                addTcpWriteClients = null;
            }

            if (!asyncUnregisterTcpEndPoints.isEmpty()) {
                unregisterTcpEndPoints = asyncUnregisterTcpEndPoints;
                asyncUnregisterTcpEndPoints = new HashMap<TcpEndPoint, Exception>();
            } else {
                unregisterTcpEndPoints = null;
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
        // for to an accepted TcpClient can be fired, and without any side effects
        if (registerTcpEndPoints != null) {
            for (TcpEndPoint tcpEndPoint : registerTcpEndPoints) {
                try {
                    tcpEndPoint.handleRegistration();
                } catch (Exception e) {
                    tcpEndPoint.handleUnregistration(e);
                }
            }
        }

        tcpClientsUpdated.clear();

        // fire only lock-free events towards client! We may end up firing "ReadReady" after a TcpClient
        // gets closed, but the event is valid in that case as well (since we cannot leave registered
        // callbacks hanging anyway)
        if (addTcpReadClients != null) {
            int index = 0;
            for (TcpClient tcpClient : addTcpReadClients) {
                try {
                    tcpClientsUpdated.add(tcpClient);
                    tcpClient.tcpReadChannel.addChannelReadyCallback(addTcpReadCallbacks.get(index++));
                } catch (Exception e) {
                    tcpClient.handleUnregistration(e);
                }
            }
        }

        // fire only lock-free events towards client! Same as ReadReady scenario.
        if (addTcpWriteClients != null) {
            int index = 0;
            for (TcpClient tcpClient : addTcpWriteClients) {
                try {
                    tcpClientsUpdated.add(tcpClient);
                    tcpClient.tcpWriteChannel.addChannelReadyCallback(addTcpWriteCallbacks.get(index++));
                } catch (Exception e) {
                    tcpClient.handleUnregistration(e);
                }
            }
        }

        if (unregisterTcpEndPoints != null) {
            for (Map.Entry<TcpEndPoint, Exception> tcpEndPoint : unregisterTcpEndPoints.entrySet()) {
                try {
                    tcpEndPoint.getKey().handleUnregistration(tcpEndPoint.getValue());
                } catch (RuntimeException re) {
                    // nothing to do
                }
            }
        }

        return true;
    }

    @Override
    public void run() {
        long timeInsideSelectNano = 0;
        long timeOutsideSelectNano = 0;

        try {
            long startSelectionNano = System.nanoTime();
            long endSelectionNano = startSelectionNano;

            while (true) {
                long now = System.currentTimeMillis();

                if (!processAccumulatedAsyncRequests()) {
                    return; // closed
                }

                long selectTimeout = 0;
                for (Iterator<Map.Entry<Long, List<PendingOps>>> iterator = resumingLaterInterestOps.entrySet().iterator(); iterator.hasNext(); ) {
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

                // Cache since it will be used inside multiple loops
                Set<SelectionKey> selectedKeys = selector.selectedKeys();

                // Debug: tcpReactor.tcpMetrics.asyncAddTcpRWClientsCount.inc(tcpClientsUpdated.size());
                // clients that have received RW interests asynchronously
                for (TcpClient tcpClient : tcpClientsUpdated) {
                    tcpClient.updateInterestOps();
                    selectedKeys.remove(tcpClient.selectionKey);
                }

                // Debug: tcpReactor.tcpMetrics.syncAddTcpRWClientsCount.inc(syncAddTcpRWClients.size());
                // clients that have received RW interests synchronously during events fired further up
                Iterator<TcpClient> syncRegisterTcpRWClientsIter = syncAddTcpRWClients.iterator();
                while (syncRegisterTcpRWClientsIter.hasNext()) {
                    TcpClient tcpClient = syncRegisterTcpRWClientsIter.next();
                    tcpClient.updateInterestOps();
                    selectedKeys.remove(tcpClient.selectionKey);
                    syncRegisterTcpRWClientsIter.remove();
                }

                Iterator<SelectionKey> selectionKeyIterator = selectedKeys.iterator();
                while (selectionKeyIterator.hasNext()) {
                    ((TcpClient) selectionKeyIterator.next().attachment()).updateInterestOps();
                    selectionKeyIterator.remove();
                }

                startSelectionNano = System.nanoTime();
                timeOutsideSelectNano += startSelectionNano - endSelectionNano;
                // Debug: tcpReactor.tcpMetrics.timeOutsideSelectNano.update(startSelectionNano - endSelectionNano, TimeUnit.NANOSECONDS);

                int updated = selector.select(selectTimeout);

                endSelectionNano = System.nanoTime();
                timeInsideSelectNano += endSelectionNano - startSelectionNano;

                // Debug: tcpReactor.tcpMetrics.timeInsideSelectNano.update(endSelectionNano - startSelectionNano, TimeUnit.NANOSECONDS);
                // Debug: tcpReactor.tcpMetrics.selectedKeysBuckets.update(updated);

                if (updated != selector.selectedKeys().size()) {
                    if (logger.isLoggable(Level.INFO)) {
                        logger.info(String.format("Assertion failure: updated [%s] != selector.selectedKeys().size() [%s]",
                            updated, selector.selectedKeys().size()));
                    }
                }

                if (logger.isLoggable(Level.INFO)) {
                    logger.info(String.format("Selector[%s] TimeIn[%s] TimeOut[%s] Keys[%s] Selected[%s]",
                        selector, timeInsideSelectNano, timeOutsideSelectNano, selector.keys().size(), updated));
                }

                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey selectionKey = iterator.next();

                    try {
                        if (selectionKey.isAcceptable()) {
                            SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
                            socketChannel.configureBlocking(false);
                            TcpSelector tcpSelector = tcpReactor.nextTcpSelector();

                            // asyncRegisterTcpEndPoint (and not registerTcpEndPoint) b/c tcpSelector could be a different selector
                            tcpSelector.asyncRegisterTcpEndPoint(
                                new TcpClient(socketChannel, tcpSelector, (TcpServer) selectionKey.attachment()));

                            iterator.remove();
                            continue;
                        }

                        TcpClient tcpClient = (TcpClient) selectionKey.attachment();

                        if (logger.isLoggable(Level.INFO)) {
                            logger.info(String.format("Selection for actor[%s] readyOps: [%s]",
                                tcpClient.getDebugId(), selectionKey.readyOps()));
                        }

                        tcpClient.handleOpsReady();
                    } catch (Exception e) {
                        // ClosedChannelException | IllegalBlockingModeException | RuntimeException from handle/Connected()/Bound()
                        ((TcpClient) selectionKey.attachment()).handleUnregistration(e);
                    }
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
        logger.severe(String.format("Exception: %s %s", e.getClass().getSimpleName(), e.getMessage()));

        // AOP wraps this call when debugging and the argument e provides useful info
        tcpReactor.close(); // bubble up the fatal exception by asking the broker to close
    }

    /**
     * The component is already in closed state, with no way to pass any exceptions to its user. That's why we silence
     * all exceptions here. Always called by the same tcpSelectorThread, servicing this instance.
     */
    private void syncClose() {
        for (SelectionKey selectionKey : selector.keys()) {
            ((TcpEndPoint) selectionKey.attachment()).setExceptionAndCloseChannel(fatalException);
        }

        for (TcpEndPoint tcpEndPoint : asyncRegisterTcpEndPoints) {
            tcpEndPoint.setExceptionAndCloseChannel(fatalException);
        }

        try {
            selector.close();
        } catch (IOException ioe) {
        }
    }
}
