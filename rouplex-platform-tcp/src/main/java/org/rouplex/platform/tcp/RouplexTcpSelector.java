package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.collections.SortedByValueMap;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RouplexTcpSelector implements Closeable {
    protected final Object lock = new Object();

    protected final RouplexTcpBinder rouplexTcpBinder;
    protected final Selector selector;
    protected final ByteBuffer readBuffer;

    @GuardedBy("lock")
    protected List<RouplexTcpEndPoint> registeringTcpEndPoints = new ArrayList<RouplexTcpEndPoint>();
    @GuardedBy("lock")
    protected Map<RouplexTcpEndPoint, Exception> unregisteringTcpEndPoints = new HashMap<RouplexTcpEndPoint, Exception>();

    protected final Map<SelectionKey, Integer> removingInterestOps = new HashMap<SelectionKey, Integer>();
    protected final SortedByValueMap<SelectionKey, Long> resumingWrites = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingReads = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingAccepts = new SortedByValueMap<SelectionKey, Long>();

    private boolean closed;

    RouplexTcpSelector(RouplexTcpBinder rouplexTcpBinder, Selector selector, int readBufferSize) {
        this.rouplexTcpBinder = rouplexTcpBinder;
        this.selector = selector;
        this.readBuffer = ByteBuffer.allocate(readBufferSize);

        start(rouplexTcpBinder.executorService);
    }

    /**
     * Add the tcpEndPoint to be registered on next cycle of selector.
     *
     * @param tcpEndPoint
     * @throws IOException
     */
    void asyncRegisterTcpEndPoint(RouplexTcpEndPoint tcpEndPoint) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("RouplexTcpSelector already closed.");
            }

            registeringTcpEndPoints.add(tcpEndPoint);
            selector.wakeup();
        }
    }

    private boolean registerTcpEndPoint(RouplexTcpEndPoint tcpEndPoint) {
        boolean keepAccepting = true;

        try {
            SelectableChannel selectableChannel = tcpEndPoint.getSelectableChannel();
            selectableChannel.configureBlocking(false);

            if (tcpEndPoint instanceof RouplexTcpClient) {
                int interestOps = SelectionKey.OP_WRITE;
                boolean connected = ((SocketChannel) selectableChannel).isConnected();
                if (!connected) {
                    interestOps |= SelectionKey.OP_CONNECT;
                }

                // Important moment where the client gets to update itself knowing is registered with the binder
                tcpEndPoint.setSelectionKey(selectableChannel.register(selector, interestOps, tcpEndPoint));

                if (connected) {
                    keepAccepting = notifyConnectedTcpClient((RouplexTcpClient) tcpEndPoint);
                }
            } else if (tcpEndPoint instanceof RouplexTcpServer) {
                tcpEndPoint.setSelectionKey(selectableChannel.register(selector, SelectionKey.OP_ACCEPT, tcpEndPoint));
                notifyBoundTcpServer((RouplexTcpServer) tcpEndPoint);
            }
        } catch (Exception e) {
            // ClosedChannelException | IllegalBlockingModeException | RuntimeException from notifyConnectedTcpClient()
            tcpEndPoint.closeSilently(e);
        }

        return keepAccepting;
    }

    /**
     * @param tcpClient
     * @return true if we should keep accepting new channels, false otherwise (not implemented yet)
     */
    private boolean notifyConnectedTcpClient(RouplexTcpClient tcpClient) {
        tcpClient.handleConnected();

        if (rouplexTcpBinder.rouplexTcpClientListener != null) {
            rouplexTcpBinder.rouplexTcpClientListener.onConnected(tcpClient);
        }

        return true;
    }

    /**
     * @param tcpServer
     *          The {@link RouplexTcpServer} instance that got bound
     */
    private void notifyBoundTcpServer(RouplexTcpServer tcpServer) {
        tcpServer.handleBound();

        if (rouplexTcpBinder.rouplexTcpServerListener != null) {
            rouplexTcpBinder.rouplexTcpServerListener.onBound(tcpServer);
        }
    }

    /**
     * For channels that closed and need to report via binder. Add the tcpEndPoint to be unregistered on next cycle of
     * selector.
     *
     * @param tcpEndPoint
     * @throws IOException
     */
    void asyncUnregisterTcpEndPoint(RouplexTcpEndPoint tcpEndPoint, Exception reason) throws IOException {
        synchronized (lock) {
            if (!closed) {
                unregisteringTcpEndPoints.put(tcpEndPoint, reason);
                selector.wakeup();
            }
        }
    }

    private void unregisterTcpEndPoint(RouplexTcpEndPoint tcpEndPoint, Exception optionalReason) {
        try {
            if (tcpEndPoint instanceof RouplexTcpClient) {
                RouplexTcpClient tcpClient = (RouplexTcpClient) tcpEndPoint;
                if (tcpClient.open) {
                    boolean drainedChannels = tcpClient.handleDisconnected(optionalReason);

                    if (rouplexTcpBinder.rouplexTcpClientListener != null) {
                        rouplexTcpBinder.rouplexTcpClientListener.onDisconnected(tcpClient, optionalReason, drainedChannels);
                    }
                }
                else {
                    tcpClient.handleConnectionFailed(optionalReason);

                    if (rouplexTcpBinder.rouplexTcpClientListener != null) {
                        rouplexTcpBinder.rouplexTcpClientListener.onConnectionFailed(tcpClient, optionalReason);
                    }
                }
            }

            else if (tcpEndPoint instanceof RouplexTcpServer) {
                RouplexTcpServer tcpServer = (RouplexTcpServer) tcpEndPoint;
                tcpServer.handleUnBound();

                if (rouplexTcpBinder.rouplexTcpServerListener != null) {
                    rouplexTcpBinder.rouplexTcpServerListener.onUnBound(tcpServer);
                }
            }
        } catch (Exception e) {
            // channel is already closed, nothing to do
        }
    }

    @GuardedBy("lock")
    private long updateInterestOpsAndCalculateSelectTimeout() {
        for (Map.Entry<SelectionKey, Integer> removingInterestOp : removingInterestOps.entrySet()) {
            SelectionKey selectionKey = removingInterestOp.getKey();// channel may have closed during the preceding loop
            if (selectionKey.isValid()) { // channel may have closed during the preceding loop
                selectionKey.interestOps(selectionKey.interestOps() & ~removingInterestOp.getValue());
            }
        }

        removingInterestOps.clear();

        long now = System.currentTimeMillis();
        long timeout = Long.MAX_VALUE;
        timeout = Math.min(timeout, calculateNextSelectTimeout(resumingAccepts, now, SelectionKey.OP_ACCEPT));
        timeout = Math.min(timeout, calculateNextSelectTimeout(resumingReads, now, SelectionKey.OP_READ));
        timeout = Math.min(timeout, calculateNextSelectTimeout(resumingWrites, now, SelectionKey.OP_WRITE));

        return timeout == Long.MAX_VALUE ? 0 : timeout;
    }

    private long calculateNextSelectTimeout(SortedByValueMap<SelectionKey, Long> resumingSelectors, long now, int op) {
        long selectTimeout = Long.MAX_VALUE;

        for (Iterator<Map.Entry<SelectionKey, Long>> iterator = resumingSelectors.sortedByValue().iterator(); iterator.hasNext(); ) {
            Map.Entry<SelectionKey, Long> resumingOp = iterator.next();

            SelectionKey selectionKey = resumingOp.getKey();// channel may have closed during the preceding loop
            if (!selectionKey.isValid()) {
                iterator.remove();
                continue;
            }

            if (resumingOp.getValue() <= now) {
                selectionKey.interestOps(selectionKey.interestOps() | op);
                iterator.remove();
            } else {
                selectTimeout = resumingOp.getValue() - now;
                break;
            }
        }

        return selectTimeout;
    }

    private void start(final ExecutorService executorService) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                Thread currentThread = Thread.currentThread();
                currentThread.setName("RouplexTcpSelector");

                try {
                    while (true) {
                        if (executorService.isShutdown() || currentThread.isInterrupted()) {
                            close();
                        }

                        long selectTimeout;
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

                            selectTimeout = updateInterestOpsAndCalculateSelectTimeout();
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

                        selector.selectedKeys().clear();
                        selector.select(selectTimeout);

                        for (SelectionKey selectionKey : selector.selectedKeys()) {
                            handleSelectedKey(selectionKey);
                        }
                    }
                } catch (Exception e) {
                    logSelectException(e);
                }

                syncClose(); // close synchronously.
            }
        });
    }

    private void handleSelectedKey(SelectionKey selectionKey) {
        try {
            if (selectionKey.isAcceptable()) {
                SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
                RouplexTcpServer rouplexTcpServer = (RouplexTcpServer) selectionKey.attachment();

                if (rouplexTcpServer.sendBufferSize != 0) {
                    socketChannel.socket().setSendBufferSize(rouplexTcpServer.sendBufferSize);
                }
                if (rouplexTcpServer.receiveBufferSize != 0) {
                    socketChannel.socket().setReceiveBufferSize(rouplexTcpServer.receiveBufferSize);
                }

                RouplexTcpSelector rouplexTcpSelector = rouplexTcpBinder.nextRouplexTcpSelector();
                rouplexTcpSelector.asyncRegisterTcpEndPoint(
                        new RouplexTcpClient(socketChannel, rouplexTcpSelector, rouplexTcpServer));

                return;
            }

            SocketChannel socketChannel = ((SocketChannel) selectionKey.channel());
            RouplexTcpClient rouplexTcpClient = (RouplexTcpClient) selectionKey.attachment();

            if (selectionKey.isConnectable()) {
                try {
                    if (!socketChannel.finishConnect()) {
                        return;
                    }

                    selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_CONNECT);
                    notifyConnectedTcpClient(rouplexTcpClient);
                } catch (Exception e) {
                    // IOException | RuntimeException (from client handling notification)
                    // TODO differentiate between failedConnection and destroyed ...
                    rouplexTcpClient.closeSilently(e);
                    return;
                }
            }

            if (selectionKey.isReadable()) {
                int read = 0;
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

                        if (!rouplexTcpClient.throttledReceiver.consumeSocketInput(readPayload) || read == -1) {
                            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
                            break;
                        }
                    }
                } catch (Exception e) {
                    // IOException | RuntimeException (from client handling received bytes)
                    if (read == 0) { // tricky way to test if exception generated during read()
                        try {
                            rouplexTcpClient.throttledReceiver.consumeSocketInput(null);
                        } catch (RuntimeException re) {
                            // fall through, since we already have the cause exception
                        }
                    }
                    rouplexTcpClient.closeSilently(e);
                    return;
                }
            }

            if (selectionKey.isWritable()) {
                while (true) {
                    ByteBuffer writeBuffer = rouplexTcpClient.throttledSender.pollFirstWriteBuffer();

                    if (writeBuffer == null) { // nothing in the queue
                        selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                        break;
                    }

                    boolean eos = !writeBuffer.hasRemaining(); // empty buffer is "marker for EOS"
                    try {
                        if (eos) {
                            socketChannel.shutdownOutput();
                        } else {
                            socketChannel.write(writeBuffer);
                            if (writeBuffer.hasRemaining()) {
                                break;
                            }
                        }

                        rouplexTcpClient.throttledSender.removeWriteBuffer(writeBuffer);
                    } catch (Exception e) {
                        // IOException | RuntimeException (from client handling resume)

                        /**
                         * Extremely rarely, a read may succeed (with 0+ bytes read), yet the next read would have
                         * produced -1. The channel would be in closed state, and the attempt to write would result in
                         * closing it. We won't be able to know if that would have been the case so we just fire
                         * "disconnect" (or null) to the receiver if we failed to write. The eosReceived check is there
                         * so that we don't fire the disconnect if the -1 has already been picked up and fired already
                         */
                        if (!rouplexTcpClient.throttledReceiver.eosReceived) {
                            try {
                                rouplexTcpClient.throttledReceiver.consumeSocketInput(null);
                            } catch (RuntimeException re) {
                                // fall through, since we already have the cause exception
                            }
                        }

                        rouplexTcpClient.closeSilently(e);
                        break;
                    }

                    if (eos) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logHandleSelectedKeyException(e);
            // Normally we should check selectionKey.isValid() before any access, and since all ops
            // are synchronous, the condition would hold between various instructions. It is easier
            // to just catch here and loop to next key though since the try/catch is needed anyways
        }
    }

    private void asyncRemoveInterestOps(SelectionKey selectionKey, int interestOps) {
        synchronized (lock) {
            Integer alreadyRemovingInterestOps = removingInterestOps.put(selectionKey, interestOps);
            if (alreadyRemovingInterestOps != null) {
                removingInterestOps.put(selectionKey, alreadyRemovingInterestOps | interestOps);
            }
        }

        selector.wakeup();
    }

    void asyncPauseRead(SelectionKey selectionKey, long resumeTimestamp) {
        synchronized (lock) {
            if (selectionKey != null && selectionKey.isValid()) {
                resumingReads.put(selectionKey, resumeTimestamp);
                asyncRemoveInterestOps(selectionKey, SelectionKey.OP_READ);
            }
        }
    }

    void asyncResumeRead(SelectionKey selectionKey) {
        synchronized (lock) {
            if (selectionKey != null && selectionKey.isValid()) {
                resumingReads.put(selectionKey, 0L);
                selector.wakeup();
            }
        }
    }

    void asyncResumeWrite(SelectionKey selectionKey) {
        synchronized (lock) {
            if (selectionKey != null && selectionKey.isValid()) {
                resumingWrites.put(selectionKey, 0L);
                selector.wakeup();
            }
        }
    }

    void asyncPauseAccept(SelectionKey selectionKey, long resumeTimestamp) {
        synchronized (lock) {
            if (selectionKey != null && selectionKey.isValid()) {
                resumingAccepts.put(selectionKey, resumeTimestamp);
                asyncRemoveInterestOps(selectionKey, SelectionKey.OP_ACCEPT);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }

            closed = true;
        }

        selector.wakeup();
    }

    private void logHandleSelectedKeyException(Exception e) {
        // AOP wraps this call for debugging
    }

    private void logSelectException(Exception e) {
        // AOP wraps this call for debugging
    }

    /**
     * The component is already in closed state, with no way to pass any exceptions to its user. That's why we silence
     * all exceptions here.
     */
    private void syncClose() {
        for (SelectionKey selectionKey : selector.keys()) {
            try {
                ((RouplexTcpEndPoint) selectionKey.attachment()).close();
            } catch (IOException ioe) {
            }
        }

        for (RouplexTcpEndPoint rouplexTcpEndPoint : registeringTcpEndPoints) {
            try {
                rouplexTcpEndPoint.close();
            } catch (IOException ioe) {
            }
        }

        try {
            selector.close();
        } catch (Exception e) {
        }
    }
}
