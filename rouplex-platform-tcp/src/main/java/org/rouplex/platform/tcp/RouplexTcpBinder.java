package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.commons.collections.SortedByValueMap;
import org.rouplex.nio.channels.SSLSelector;
import org.rouplex.platform.io.Throttle;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpBinder implements Closeable {
    protected final static byte[] EOS = new byte[0];
    protected final Object lock = new Object();

    protected final Selector selector;
    protected final ExecutorService executorService;
    protected final boolean sharedExecutorService;
    protected final ByteBuffer readBuffer;

    @GuardedBy("lock")
    protected List<RouplexTcpEndPoint> registeringTcpEndPoints = new ArrayList<RouplexTcpEndPoint>();
    @GuardedBy("lock")
    protected Map<RouplexTcpEndPoint, Exception> unregisteringTcpEndPoints = new HashMap<RouplexTcpEndPoint, Exception>();
    protected final Map<SelectionKey, Integer> removingInterestOps = new HashMap<SelectionKey, Integer>();
    protected final SortedByValueMap<SelectionKey, Long> resumingWrites = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingReads = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingAccepts = new SortedByValueMap<SelectionKey, Long>();

    @Nullable
    protected RouplexTcpClientListener rouplexTcpClientListener;
    @Nullable
    protected RouplexTcpServerListener rouplexTcpServerListener;

    private static final ThreadFactory deamonThreadFactory = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }
    };

    private boolean closed;

    public RouplexTcpBinder() throws IOException {
        this(SSLSelector.open());
    }

    public RouplexTcpBinder(Selector selector) {
        this(selector, null);
    }

    public RouplexTcpBinder(Selector selector, ExecutorService executorService) {
        this(selector, executorService, 1024 * 1024);
    }

    public RouplexTcpBinder(Selector selector, ExecutorService executorService, int readBufferSize) {
        this.selector = selector;
        this.executorService = (sharedExecutorService = executorService != null)
                ? executorService : Executors.newSingleThreadExecutor(deamonThreadFactory);
        readBuffer = ByteBuffer.allocate(readBufferSize);

        start();
    }

    /**
     * Add the channel to be registered on next cycle of selector.
     *
     * @param tcpEndPoint
     * @throws IOException
     */
    void asyncRegisterTcpEndPoint(RouplexTcpEndPoint tcpEndPoint) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("RouplexTcpBinder already closed.");
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

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onConnected(tcpClient);
        }

        return true;
    }

    /**
     * @param tcpServer
     *          The {@link RouplexTcpServer} instance that got bound
     */
    private void notifyBoundTcpServer(RouplexTcpServer tcpServer) {
        tcpServer.handleBound();

        if (rouplexTcpServerListener != null) {
            rouplexTcpServerListener.onBound(tcpServer);
        }
    }

    // For channels that closed and need to report via binder
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

                    if (rouplexTcpClientListener != null) {
                        rouplexTcpClientListener.onDisconnected(tcpClient, optionalReason, drainedChannels);
                    }
                }
                else {
                    tcpClient.handleConnectionFailed(optionalReason);

                    if (rouplexTcpClientListener != null) {
                        rouplexTcpClientListener.onConnectionFailed(tcpClient, optionalReason);
                    }
                }
            }

            else if (tcpEndPoint instanceof RouplexTcpServer) {
                RouplexTcpServer tcpServer = (RouplexTcpServer) tcpEndPoint;
                tcpServer.handleUnBound();

                if (rouplexTcpServerListener != null) {
                    rouplexTcpServerListener.onUnBound(tcpServer);
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

    private void start() {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                Thread currentThread = Thread.currentThread();
                currentThread.setName("RouplexTcpBinder");

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
                socketChannel.socket().setSendBufferSize(rouplexTcpServer.sendBufferSize);
                socketChannel.socket().setReceiveBufferSize(rouplexTcpServer.receiveBufferSize);
                registerTcpEndPoint(new RouplexTcpClient(socketChannel, RouplexTcpBinder.this, rouplexTcpServer));
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
                            readPayload = EOS;
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

        if (!sharedExecutorService) {
            executorService.shutdownNow();
        }

        try {
            selector.close();
        } catch (Exception e) {
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    // In the future, if needed, we can support this
    Throttle throttle;
    public Throttle getTcpClientAcceptThrottle() {
        return throttle;
    }

    /**
     * Whoever wants to know about added / removed {@link RouplexTcpClient}s
     *
     * @param rouplexTcpClientListener
     */
    public void setRouplexTcpClientListener(@Nullable RouplexTcpClientListener rouplexTcpClientListener) {
        synchronized (lock) {
            if (this.rouplexTcpClientListener != null) {
                throw new IllegalStateException("RouplexTcpClientListener already set.");
            }

            this.rouplexTcpClientListener = rouplexTcpClientListener;
        }
    }

    /**
     * Whoever wants to know about added / removed {@link RouplexTcpServer}s
     *
     * @param rouplexTcpServerListener
     */
    public void setRouplexTcpServerListener(@Nullable RouplexTcpServerListener rouplexTcpServerListener) {
        synchronized (lock) {
            if (this.rouplexTcpServerListener != null) {
                throw new IllegalStateException("RouplexTcpServerListener already set.");
            }

            this.rouplexTcpServerListener = rouplexTcpServerListener;
        }
    }
}
