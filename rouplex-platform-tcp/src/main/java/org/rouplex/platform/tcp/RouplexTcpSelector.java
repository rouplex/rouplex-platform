package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

/**
 * Internal class, not to be accessed directly by the user. It performs tasks related to registering and unregistering
 * channels with the selector that it owns, selecting on those channels and handling the ready ops by performing them,
 * then invoking the appropriate handlers to perform the rest of the handling.
 *
 * For example, it will perform a {@link SocketChannel#finishConnect()} when it notices the channel is connectable,
 * and if successful will call the related client's connected(..) event; or it will perform a
 * {@link SocketChannel#read(ByteBuffer)} when it notices the channel is readable, then invoke the appropriate client's
 * throttled receiver to handle the received data and so on with the write.
 *
 * The number of instances of this class will be usually related to the number of the CPU's available in the host.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RouplexTcpSelector implements Runnable {
    private final Object lock = new Object();

    private final RouplexTcpBroker rouplexTcpBroker;
    private final Selector selector;
    private final ByteBuffer readBuffer;

    @GuardedBy("lock")
    private List<RouplexTcpEndPoint> registeringTcpEndPoints = new ArrayList<RouplexTcpEndPoint>();
    @GuardedBy("lock")
    private Map<RouplexTcpEndPoint, Exception> unregisteringTcpEndPoints = new HashMap<RouplexTcpEndPoint, Exception>();
    @GuardedBy("lock")
    private List<PendingOps> pausingInterestOps = new ArrayList<PendingOps>();
    @GuardedBy("lock")
    private List<PendingOps> resumingInterestOps = new ArrayList<PendingOps>();

    private final SortedMap<Long, List<PendingOps>> resumingLaterInterestOps = new TreeMap<Long, List<PendingOps>>();
    private boolean closed;

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

    RouplexTcpSelector(RouplexTcpBroker rouplexTcpBroker, Selector selector, int readBufferSize) {
        this.rouplexTcpBroker = rouplexTcpBroker;
        this.selector = selector;
        this.readBuffer = ByteBuffer.allocate(readBufferSize);
    }

    /**
     * Add the endpoint to be registered on next cycle of selector. We merely keep a reference on it, and wakeup the
     * background thread which is tasked with selecting as well as registering new / unregistering old endpoints.
     *
     * @param tcpEndPoint
     *          the endpoint to be added for selection inside the main loop
     * @throws IOException
     *          if the instance has already been closed
     */
    void asyncRegisterTcpEndPoint(RouplexTcpEndPoint tcpEndPoint) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("RouplexTcpSelector already closed.");
            }

            registeringTcpEndPoints.add(tcpEndPoint);
        }

        selector.wakeup();
    }

    /**
     * Perform the registration of the endpoint in the context of the background thread.
     *
     * @param tcpEndPoint
     *          the endpoint to be registered
     * @return
     *          true if we should be keep accepting endpoints
     */
    private boolean registerTcpEndPoint(RouplexTcpEndPoint tcpEndPoint) {
        boolean keepAccepting = true;

        try {
            SelectableChannel selectableChannel = tcpEndPoint.getSelectableChannel();
            selectableChannel.configureBlocking(false);

            if (tcpEndPoint instanceof RouplexTcpClient) {
                RouplexTcpClient tcpClient = (RouplexTcpClient) tcpEndPoint;
                int interestOps = SelectionKey.OP_WRITE;
                boolean connected = ((SocketChannel) selectableChannel).isConnected();
                if (!connected) {
                    interestOps |= SelectionKey.OP_CONNECT;
                }

                // Important moment where the client gets to update itself knowing is registered with the selector
                tcpClient.setSelectionKey(selectableChannel.register(selector, interestOps, tcpEndPoint));

                if (connected) {
                    tcpClient.handleConnected();
                }
            } else if (tcpEndPoint instanceof RouplexTcpServer) {
                selectableChannel.register(selector, SelectionKey.OP_ACCEPT, tcpEndPoint);
                RouplexTcpServer tcpServer = (RouplexTcpServer) tcpEndPoint;
                tcpServer.handleBound();
            }
        } catch (Exception e) {
            // ClosedChannelException | IllegalBlockingModeException | RuntimeException from handle/Connected()/Bound()
            unregisterTcpEndPoint(tcpEndPoint, e);
        }

        return keepAccepting;
    }

    /**
     * For channels that closed and need to report via binder. Add the tcpEndPoint to be unregistered on next cycle of
     * selector.
     *
     * @param tcpEndPoint
     *          the endpoint that needs to be unregistered
     * @param optionalReason
     *          if there was an exception which resulted in the endpoint being closed, null otherwise
     */
    void asyncUnregisterTcpEndPoint(RouplexTcpEndPoint tcpEndPoint, Exception optionalReason) {
        synchronized (lock) {
            unregisteringTcpEndPoints.put(tcpEndPoint, optionalReason);
        }

        selector.wakeup();
    }

    /**
     * Perform the un-registration of the endpoint in the context of the background thread.
     *
     * @param tcpEndPoint
     *          the endpoint to be registered
     * @param optionalReason
     *          if there was an exception which resulted in the endpoint being closed, null otherwise
     */
    private void unregisterTcpEndPoint(RouplexTcpEndPoint tcpEndPoint, Exception optionalReason) {
        try {
            if (tcpEndPoint instanceof RouplexTcpClient) {
                RouplexTcpClient tcpClient = (RouplexTcpClient) tcpEndPoint;
                if (tcpClient.open) {
                    tcpClient.handleDisconnected(optionalReason);
                } else {
                    tcpClient.handleConnectionFailed(optionalReason);
                }
            }

            else if (tcpEndPoint instanceof RouplexTcpServer) {
                RouplexTcpServer tcpServer = (RouplexTcpServer) tcpEndPoint;
                tcpServer.handleUnBound();
            }
        } catch (Exception e) {
            // channel is already closed, nothing to do
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                long now = System.currentTimeMillis();
                List<RouplexTcpEndPoint> registerTcpEndPoints;
                Map<RouplexTcpEndPoint, Exception> unregisterTcpEndPoints;

                synchronized (lock) {
                    if (closed) {
                        break;
                    }

                    if (registeringTcpEndPoints.isEmpty()) {
                        registerTcpEndPoints = null;
                    } else {
                        registerTcpEndPoints = registeringTcpEndPoints;
                        registeringTcpEndPoints = new ArrayList<RouplexTcpEndPoint>();
                    }

                    if (unregisteringTcpEndPoints.isEmpty()) {
                        unregisterTcpEndPoints = null;
                    } else {
                        unregisterTcpEndPoints = unregisteringTcpEndPoints;
                        unregisteringTcpEndPoints = new HashMap<RouplexTcpEndPoint, Exception>();
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
                    for (RouplexTcpEndPoint tcpEndPoint : registerTcpEndPoints) {
                        registerTcpEndPoint(tcpEndPoint);
                    }
                }

                if (unregisterTcpEndPoints != null) {
                    for (Map.Entry<RouplexTcpEndPoint, Exception> tcpEndPoint : unregisterTcpEndPoints.entrySet()) {
                        unregisterTcpEndPoint(tcpEndPoint.getKey(), tcpEndPoint.getValue());
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

                selector.selectedKeys().clear();
                selector.select(selectTimeout);

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
                RouplexTcpSelector tcpSelector = rouplexTcpBroker.nextRouplexTcpSelector();

                // asyncRegisterTcpEndPoint (and not registerTcpEndPoint) b/c tcpSelector is a different selector
                tcpSelector.asyncRegisterTcpEndPoint(
                        new RouplexTcpClient(socketChannel, tcpSelector, (RouplexTcpServer) selectionKey.attachment()));

                return;
            }

            SocketChannel socketChannel = ((SocketChannel) selectionKey.channel());
            RouplexTcpClient tcpClient = (RouplexTcpClient) selectionKey.attachment();

            if (selectionKey.isConnectable()) {
                try {
                    if (!socketChannel.finishConnect()) {
                        return;
                    }

                    selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_CONNECT);
                    tcpClient.handleConnected();
                } catch (Exception e) {
                    // IOException | RuntimeException (from client handling notification)
                    // TODO differentiate between failedConnection and destroyed ...
                    unregisterTcpEndPoint(tcpClient, e);
                    return;
                }
            }

            if (selectionKey.isReadable()) {
                int read;
                try {
                    while ((read = socketChannel.read(readBuffer)) != 0) {
                        byte[] readPayload;
                        if (read == -1) {
                            readPayload = RouplexTcpClient.EOS_BA;
                        } else {
                            readPayload = new byte[read];
                            System.arraycopy(readBuffer.array(), 0, readPayload, 0, readBuffer.position());
                            readBuffer.clear();
                        }

                        long resumeTimestamp = tcpClient.throttledReceiver.handleSocketInput(readPayload);
                        if (resumeTimestamp == -2) {
                            unregisterTcpEndPoint(tcpClient, null);
                            return;
                        }

                        if (resumeTimestamp == -1) {
                            continue;
                        }

                        if (resumeTimestamp == 0) {
                            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
                        } else {
                            pauseInterestOps(new PendingOps(selectionKey, SelectionKey.OP_READ, resumeTimestamp));
                        }
                        break;
                    }
                } catch (Exception e) {
                    // IOException | RuntimeException (from client handling received bytes)
                    handleReadWriteUserException(tcpClient, e);
                    return;
                }
            }

            if (selectionKey.isWritable()) {
                while (true) {
                    ByteBuffer writeBuffer = tcpClient.throttledSender.pollFirstWriteBuffer();

                    if (writeBuffer == null) { // nothing in the queue
                        selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                        break;
                    }

                    boolean eos = !writeBuffer.hasRemaining(); // empty buffer is "marker for EOS"
                    try {
                        if (eos) {
                            // jdk1.7+ socketChannel.shutdownOutput();
                            socketChannel.socket().shutdownOutput();
                        } else {
                            socketChannel.write(writeBuffer);
                            if (writeBuffer.hasRemaining()) {
                                break;
                            }
                        }

                        if (tcpClient.throttledSender.removeWriteBuffer(writeBuffer) == -2) {
                            unregisterTcpEndPoint(tcpClient, null);
                            return;
                        }
                    } catch (Exception e) {
                        // IOException | RuntimeException (from client handling resume) | ClosedKeyException (detailed)
                        // Especially in the case of SSLSocketChannel, the remote peer may have shutdown its ssl output
                        // stream which produces ssl control data handled by the local peer's SSLEngine and which in
                        // turn will close the local peer's outbound ssl stream. So the user is just realizing that its
                        // output stream is not open anymore.
                        handleReadWriteUserException(tcpClient, e);
                        break;
                    }

                    if (eos) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            handleSelectedKeyException(e);
            // Normally we should check selectionKey.isValid() before any access, and since all ops
            // are synchronous, the condition would hold between various instructions. It is easier
            // to just catch here and loop to next key though since the try/catch is needed anyways
        }
    }

    private void handleReadWriteUserException(RouplexTcpClient tcpClient, Exception e) {
        if (!tcpClient.throttledReceiver.eosReceived) { // if eosReceived then receiver already closed
            try {
                tcpClient.throttledReceiver.handleSocketInput(null);
            } catch (RuntimeException re) {
                // fall through, since we already have the cause exception
            }
        }

        unregisterTcpEndPoint(tcpClient, e);
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
     * @param selectionKey
     *          the key for which we need the new interest ops
     */
    void asyncPauseInterestOps(SelectionKey selectionKey, int interestOps, long resumeTimestamp) {
        synchronized (lock) {
            try {
                pausingInterestOps.add(new PendingOps(selectionKey, interestOps, resumeTimestamp));
            } catch (Exception e) {
                // RouplexTcpServer may have been built using an existing socketChannel, so its owner could close it
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
     * @param selectionKey
     *          the key for which we need the new interest ops
     */
    void asyncResumeInterestOps(SelectionKey selectionKey, int interestOps) {
        synchronized (lock) {
            try {
                resumingInterestOps.add(new PendingOps(selectionKey, interestOps, 0));
            } catch (Exception e) {
                // RouplexTcpServer may have been built using an existing socketChannel, so its owner could close it
                // at any time, hence cancelling this selectionKey -- we will just ignore this, since the key will be
                // unregistered with this channel in the next select() round
            }
        }

        selector.wakeup();
    }

    void requestClose() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            closed = true;
        }

        selector.wakeup();
    }

    void handleSelectedKeyException(Exception e) {
        // AOP wraps this call when debugging
    }

    void handleSelectException(Exception e) {
        // AOP wraps this call when debugging and the argument e provides useful info
        rouplexTcpBroker.close(); // bubble up the fatal exception by asking the broker to close
    }

    /**
     * The component is already in closed state, with no way to pass any exceptions to its user. That's why we silence
     * all exceptions here. Always called by the same thread, servicing this instance.
     */
    private void syncClose() {
        for (SelectionKey selectionKey : selector.keys()) {
            try {
                ((RouplexTcpEndPoint) selectionKey.attachment()).close();
            } catch (RuntimeException ioe) { // IOE
            }
        }

        for (RouplexTcpEndPoint rouplexTcpEndPoint : registeringTcpEndPoints) {
            try {
                rouplexTcpEndPoint.close();
            } catch (RuntimeException ioe) { // IOE
            }
        }

        try {
            selector.close();
        } catch (IOException ioe) {
        }
    }
}
