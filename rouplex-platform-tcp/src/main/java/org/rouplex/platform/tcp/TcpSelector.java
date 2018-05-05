package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal class, not to be accessed directly by the user. It performs tasks related to registering and unregistering
 * channels with the selector that it owns, selecting on those channels and handling the ready ops by performing them,
 * then invoking the appropriate handlers to perform the rest of the handling.
 * <p/>
 * For example, it will perform a {@link SocketChannel#finishConnect()} when it notices the channel is connectable,
 * and if successful will call the related client's connected(..) event; or it will perform a
 * {@link SocketChannel#read(ByteBuffer)} when it notices the channel is readable, then invoke the appropriate client's
 * throttled receiver to handle the received data and so on with the write.
 * <p/>
 * The number of instances of this class will be usually related to the number of the CPU's available in the host.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class TcpSelector implements Runnable {
    private static final Logger logger = Logger.getLogger(TcpSelector.class.getSimpleName());
    private final Object lock = new Object();

    private final TcpBroker tcpBroker;
    final Selector selector;

    @GuardedBy("lock")
    private List<TcpEndPoint> registeringTcpEndPoints = new ArrayList<TcpEndPoint>();
    @GuardedBy("lock")
    private Map<TcpEndPoint, Exception> unregisteringTcpEndPoints = new HashMap<TcpEndPoint, Exception>();
    @GuardedBy("lock")
    private List<TcpClient> updatingTcpClients = new ArrayList<TcpClient>();
    @GuardedBy("lock")
    private List<PendingOps> pausingInterestOps = new ArrayList<PendingOps>();
    @GuardedBy("lock")
    private List<PendingOps> resumingInterestOps = new ArrayList<PendingOps>();

    private final SortedMap<Long, List<PendingOps>> resumingLaterInterestOps = new TreeMap<Long, List<PendingOps>>();
    private boolean closed;
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

    TcpSelector(TcpBroker tcpBroker, Selector selector) {
        this.tcpBroker = tcpBroker;
        this.selector = selector;
    }

    /**
     * Add the endpoint to be registered on next cycle of selector. We merely keep a reference on it, and wakeup the
     * background thread which is tasked with selecting as well as registering new / unregistering old endpoints.
     *
     * @param tcpEndPoint the endpoint to be added for selection inside the main loop
     * @throws IOException if the instance has already been closed
     */
    void asyncRegisterTcpEndPoint(TcpEndPoint tcpEndPoint) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("TcpSelector already closed.");
            }

            registeringTcpEndPoints.add(tcpEndPoint);
        }

        selector.wakeup();
    }

    /**
     * Add a endpoint whose interestOps need to be updated on next cycle of selector. We merely keep a reference on it,
     * and wakeup the selector which will call the tcpEndpoint to update the corresponding selectionKey.
     *
     * @param tcpClient the tcpClient whose interestOps will be updated on next cycle of selector
     * @throws IOException if the instance has already been closed
     */
    void asyncUpdateTcpClient(TcpClient tcpClient) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("TcpSelector already closed.");
            }

            updatingTcpClients.add(tcpClient);
        }

        selector.wakeup();
    }

    /**
     * For channels that closed and need to report via binder. Add the tcpEndPoint to be unregistered on next cycle of
     * selector.
     *
     * @param tcpEndPoint    the endpoint that needs to be unregistered
     * @param optionalReason if there was an exception which resulted in the endpoint being closed, null otherwise
     */
    void asyncUnregisterTcpEndPoint(TcpEndPoint tcpEndPoint, Exception optionalReason) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("TcpSelector already closed.");
            }

            unregisteringTcpEndPoints.put(tcpEndPoint, optionalReason);
        }

        selector.wakeup();
    }

    AtomicLong timeInsideSelectNano = new AtomicLong();
    AtomicLong timeOutsideSelectNano = new AtomicLong();

    @Override
    public void run() {
        try {
            long startSelectionNano = System.nanoTime();
            long endSelectionNano = startSelectionNano;

            while (true) {
                long now = System.currentTimeMillis();
                List<TcpEndPoint> registerTcpEndPoints;
                List<TcpClient> updateTcpClients;
                Map<TcpEndPoint, Exception> unregisterTcpEndPoints;

                synchronized (lock) {
                    if (closed) {
                        break;
                    }

                    if (registeringTcpEndPoints.isEmpty()) {
                        registerTcpEndPoints = null;
                    } else {
                        registerTcpEndPoints = registeringTcpEndPoints;
                        registeringTcpEndPoints = new ArrayList<TcpEndPoint>();
                    }

                    if (updatingTcpClients.isEmpty()) {
                        updateTcpClients = null;
                    } else {
                        updateTcpClients = updatingTcpClients;
                        updatingTcpClients = new ArrayList<TcpClient>();
                    }

                    if (unregisteringTcpEndPoints.isEmpty()) {
                        unregisterTcpEndPoints = null;
                    } else {
                        unregisterTcpEndPoints = unregisteringTcpEndPoints;
                        unregisteringTcpEndPoints = new HashMap<TcpEndPoint, Exception>();
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

                if (registerTcpEndPoints != null) {
                    // fire only lock-free events towards client! The trade off is that a new channel may fire
                    // after a close() call, but that same channel will be closed shortly after anyway
                    for (TcpEndPoint tcpEndPoint : registerTcpEndPoints) {
                        try {
                            tcpEndPoint.handleRegistration();
                        } catch (Exception e) {
                            tcpEndPoint.handleUnregistration(e);
                        }
                    }
                }

                if (updateTcpClients != null) {
                    // fire only lock-free events towards client! The trade off is that a new channel may fire
                    // after a close() call, but that same channel will be closed shortly after anyway
                    for (TcpClient tcpClient : updateTcpClients) {
                        try {
                            tcpClient.handlePreSelectUpdates();
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

                startSelectionNano = System.nanoTime();
                timeOutsideSelectNano.addAndGet(startSelectionNano - endSelectionNano);

                selector.selectedKeys().clear();
                int updated = selector.select(selectTimeout);

                if (updated != selector.selectedKeys().size()) {
                    if (logger.isLoggable(Level.INFO)) {
                        logger.info(String.format("Assertion failure: updated [%s] != selector.selectedKeys().size() [%s]",
                            updated, selector.selectedKeys().size()));
                    }
                }

                endSelectionNano = System.nanoTime();
                timeInsideSelectNano.addAndGet(endSelectionNano - startSelectionNano);

                if (logger.isLoggable(Level.INFO)) {
                    logger.info(String.format("Selector[%s] TimeIn[%s] TimeOut[%s] Keys[%s] Selected[%s]",
                        selector, timeInsideSelectNano.get(), timeOutsideSelectNano.get(), selector.keys().size(), updated));
                }

                for (SelectionKey selectionKey : selector.selectedKeys()) {
                    handleSelectedKey(selectionKey);
                }
            }
        } catch (Exception e) {
            handleSelectException(e);
        }

        syncClose(); // close synchronously.
    }

    void handleSelectedKey(SelectionKey selectionKey) {
        try {
            if (selectionKey.isAcceptable()) {
                SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
                socketChannel.configureBlocking(false);
                TcpSelector tcpSelector = tcpBroker.nextTcpSelector();

                // asyncRegisterTcpEndPoint (and not registerTcpEndPoint) b/c tcpSelector is a different selector
                tcpSelector.asyncRegisterTcpEndPoint(
                    new TcpClient(socketChannel, tcpSelector, (TcpServer) selectionKey.attachment()));

                return;
            }

            TcpClient tcpClient = (TcpClient) selectionKey.attachment();

            if (logger.isLoggable(Level.INFO)) {
                logger.info(String.format("Selection for actor[%s] readyOps: [%s]",
                    tcpClient.getDebugId(), selectionKey.readyOps()));
            }

            try {
                tcpClient.handlePostSelectUpdates();
            } catch (Exception e) {
                // ClosedChannelException | IllegalBlockingModeException | RuntimeException from handle/Connected()/Bound()
                tcpClient.handleUnregistration(e);
            }
        } catch (Exception e) {
            handleSelectedKeyException(e);
            // Normally we should check selectionKey.isValid() before any access, and since all ops
            // are synchronous, the condition would hold between various instructions. It is easier
            // to just catch here and loop to next key though since the try/catch is needed anyways
        }
    }

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
     * to remove the interestOps then wakeup the selector so that the service thread can perform the update
     * synchronously on next cycle; the selector will be instructed to select for the remainder of the time (or less),
     * and will be adding the interest ops back, then remove the entry from the queue.
     *
     * @param selectionKey the key for which we need the new interest ops
     */
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
     * to remove the interestOps then wakeup the selector so that the service thread can perform the update
     * synchronously on next cycle; the selector will be instructed to select for the remainder of the time (or less),
     * and will be adding the interest ops back, then remove the entry from the queue.
     *
     * @param selectionKey the key for which we need the new interest ops
     */
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
        logger.severe(String.format("Closed. Reason: %s %s", e.getClass().getSimpleName(), e.getMessage()));

        // AOP wraps this call when debugging and the argument e provides useful info
        tcpBroker.close(); // bubble up the fatal exception by asking the broker to close
    }

    /**
     * The component is already in closed state, with no way to pass any exceptions to its user. That's why we silence
     * all exceptions here. Always called by the same thread, servicing this instance.
     */
    private void syncClose() {
        for (SelectionKey selectionKey : selector.keys()) {
            ((TcpEndPoint) selectionKey.attachment()).setExceptionAndCloseChannel(fatalException);
        }

        for (TcpEndPoint tcpEndPoint : registeringTcpEndPoints) {
            tcpEndPoint.setExceptionAndCloseChannel(fatalException);
        }

        try {
            selector.close();
        } catch (IOException ioe) {
        }
    }
}
