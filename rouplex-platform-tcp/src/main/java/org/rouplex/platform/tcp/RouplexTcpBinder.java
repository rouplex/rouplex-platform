package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.commons.collections.SortedByValueMap;
import org.rouplex.nio.channels.spi.SSLSelector;
import org.rouplex.platform.rr.NotificationListener;
import org.rouplex.platform.rr.Throttle;

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

    protected List<RouplexTcpChannel> registeringChannels = new ArrayList<RouplexTcpChannel>();
    protected List<RouplexTcpChannel> unregisteringChannels = new ArrayList<RouplexTcpChannel>();
    protected final Map<SelectionKey, Integer> removingInterestOps = new HashMap<SelectionKey, Integer>();
    protected final SortedByValueMap<SelectionKey, Long> resumingWrites = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingReads = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingAccepts = new SortedByValueMap<SelectionKey, Long>();

    @Nullable
    protected NotificationListener<RouplexTcpClient> rouplexTcpClientConnectedListener;
    @Nullable
    protected NotificationListener<RouplexTcpClient> rouplexTcpClientClosedListener;

    private static final ThreadFactory deamonThreadFactory = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }
    };

    // or isClosed, the same semantics
    private boolean isClosing;

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
        this.selector = selector; // this will be a little trickier with ssl, punting for now
        this.executorService = (sharedExecutorService = executorService != null)
                ? executorService : Executors.newCachedThreadPool(deamonThreadFactory);
        readBuffer = ByteBuffer.allocate(readBufferSize);

        start();
    }

    /**
     * Add the channel to be registered on next cycle of selector.
     *
     * @param rouplexTcpChannel
     * @throws IOException
     */
    void asyncRegisterTcpChannel(RouplexTcpChannel rouplexTcpChannel) throws IOException {
        synchronized (lock) {
            if (isClosing) {
                throw new IOException("RouplexTcpBinder already closed.");
            }

            registeringChannels.add(rouplexTcpChannel);
            selector.wakeup();
        }
    }

    private boolean registerTcpChannel(RouplexTcpChannel tcpChannel) {
        boolean keepAccepting = true;

        try {
            SelectableChannel selectableChannel = tcpChannel.getSelectableChannel();
            selectableChannel.configureBlocking(false);

            if (tcpChannel instanceof RouplexTcpClient) {
                int interestOps = SelectionKey.OP_WRITE;
                boolean connected = ((SocketChannel) selectableChannel).isConnected();
                if (!connected) {
                    interestOps |= SelectionKey.OP_CONNECT;
                }

                // Important moment where the client gets to update itself knowing is registered with the binder
                tcpChannel.setSelectionKey(selectableChannel.register(selector, interestOps, tcpChannel));

                if (connected) {
                    keepAccepting = notifyConnectedTcpClient((RouplexTcpClient) tcpChannel);
                }
            } else if (tcpChannel instanceof RouplexTcpServer) {
                tcpChannel.setSelectionKey(selectableChannel.register(selector, SelectionKey.OP_ACCEPT, tcpChannel));
            }
        } catch (Exception e) {
            // ClosedChannelException | IllegalBlockingModeException | RuntimeException from client.onEvent()
            try {
                tcpChannel.close();
            } catch (IOException ioe) {
                // the error has already been fired
            }
        }

        return keepAccepting;
    }

    private void unregisterTcpChannel(RouplexTcpChannel tcpChannel) {
        try {
            if (rouplexTcpClientClosedListener != null && tcpChannel instanceof RouplexTcpClient) {
                rouplexTcpClientClosedListener.onEvent((RouplexTcpClient) tcpChannel);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // channel is already closed
        }
    }

    /**
     * @param tcpClient
     * @return true if we should keep accepting new channels, false otherwise (not implemented yet)
     * @throws IOException
     */
    private boolean notifyConnectedTcpClient(RouplexTcpClient tcpClient) throws IOException {
        if (tcpClient.rouplexTcpClientConnectedListener != null) {
            try {
                tcpClient.rouplexTcpClientConnectedListener.onEvent(tcpClient);
            } catch (Exception e) {
                e.printStackTrace();
                // we neglect client thrown exceptions during event handling
            }
        }

        if (rouplexTcpClientConnectedListener != null) {
            try {
                rouplexTcpClientConnectedListener.onEvent(tcpClient);
            } catch (Exception e) {
                e.printStackTrace();
                // we neglect client thrown exceptions during event handling
            }
        }

        return true;
    }

    // For channels that closed and need to report via binder
    void asyncNotifyClosedTcpChannel(RouplexTcpChannel tcpChannel) throws IOException {
        synchronized (lock) {
            if (isClosing) {
                throw new IOException("RouplexTcpBinder already closed.");
            }

            unregisteringChannels.add(tcpChannel);
            selector.wakeup();
        }
    }

    /**
     * Assertions: lock acquired
     *
     * @return
     */
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
                currentThread.setName("Rouplex-Platform-TcpBinder");

                try {
                    while (true) {
                        if (executorService.isShutdown() || currentThread.isInterrupted()) {
                            close();
                        }

                        long selectTimeout;
                        List<RouplexTcpChannel> registerChannels;
                        List<RouplexTcpChannel> unregisterChannels;
                        synchronized (lock) {
                            if (isClosing) {
                                break;
                            }

                            if (registeringChannels.isEmpty()) {
                                registerChannels = null;
                            } else {
                                registerChannels = registeringChannels;
                                registeringChannels = new ArrayList<RouplexTcpChannel>();
                            }

                            if (unregisteringChannels.isEmpty()) {
                                unregisterChannels = null;
                            } else {
                                unregisterChannels = unregisteringChannels;
                                unregisteringChannels = new ArrayList<RouplexTcpChannel>();
                            }

                            selectTimeout = updateInterestOpsAndCalculateSelectTimeout();
                        }

                        if (registerChannels != null) {
                            // fire only lock-free events towards client! The trade off is that a new channel may fire
                            // after a close() call, but that same channel will be closed shortly after anyway
                            for (RouplexTcpChannel tcpChannel : registerChannels) {
                                registerTcpChannel(tcpChannel);
                            }
                        }

                        if (unregisterChannels != null) {
                            for (RouplexTcpChannel closedChannel : unregisterChannels) {
                                unregisterTcpChannel(closedChannel);
                            }
                        }

                        selector.selectedKeys().clear();
                        selector.select(selectTimeout);

                        for (SelectionKey selectionKey : selector.selectedKeys()) {
                            handleSelectedKey(selectionKey);
                        }
                    }
                } catch (Exception ioe) { // aaa
                    ioe.printStackTrace();
                    // something major, close the binder
                }

                syncClose(); // close synchronously.
            }
        });
    }

    private void handleSelectedKey(SelectionKey selectionKey) {
        try {
            if (selectionKey.isAcceptable()) {
                SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
                registerTcpChannel(new RouplexTcpClient(socketChannel, RouplexTcpBinder.this,
                        (RouplexTcpServer) selectionKey.attachment()));
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
                    rouplexTcpClient.closeSilently();
                    return;
                }
            }

            if (selectionKey.isReadable()) {
                int read = 0;
                try {
                    if ((read = socketChannel.read(readBuffer)) != 0) {
                        byte[] readPayload;

                        switch (read) {
                            case -1:
                                readPayload = EOS;
                                break;
                            default:
                                readPayload = new byte[read];
                                System.arraycopy(readBuffer.array(), 0, readPayload, 0, readBuffer.position());
                                readBuffer.clear();
                        }

                        if (!rouplexTcpClient.throttledReceiver.consumeSocketInput(readPayload) || read == -1) {
                            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
                        }
                    }
                } catch (Exception e) {
                    // IOException | RuntimeException (from client handling received bytes)
                    if (read == 0) { // tricky way to test if exception generated during read()
                        try {
                            rouplexTcpClient.throttledReceiver.consumeSocketInput(null);
                        } catch (RuntimeException re) {
                            // fall through
                        }
                    }
                    rouplexTcpClient.closeSilently();
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
                         * "disconnect" (or null) to the receiver. The eosReceived check is there so that we don't fire
                         * the disconnect if the -1 has already been picked up and fired already
                         */
                        if (!rouplexTcpClient.throttledReceiver.eosReceived) {
                            try {
                                rouplexTcpClient.throttledReceiver.consumeSocketInput(null);
                            } catch (RuntimeException re) {
                                // fall through
                            }
                        }

                        rouplexTcpClient.closeSilently();
                        break;
                    }

                    if (eos) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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

    public static int asyncResumeWrite = 0;

    void asyncResumeWrite(SelectionKey selectionKey) {
        synchronized (lock) {
            if (selectionKey != null && selectionKey.isValid()) {
                asyncResumeWrite++;
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
            if (isClosing) {
                return;
            }

            isClosing = true;
        }

        selector.wakeup();
    }

    private void syncClose() {
        for (SelectionKey selectionKey : selector.keys()) {
            try {
                ((RouplexTcpChannel) selectionKey.attachment()).close();
            } catch (IOException ioe) {
            }
        }

        for (RouplexTcpChannel rouplexTcpChannel : registeringChannels) {
            try {
                rouplexTcpChannel.close();
            } catch (IOException ioe) {
            }
        }

        if (!sharedExecutorService) {
            executorService.shutdownNow();
        }

        try {
            selector.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return isClosing;
        }
    }

    // In the future, if needed, we can support this
    Throttle throttle;
    public Throttle getTcpClientAcceptThrottle() {
        return throttle;
    }

    /**
     * Whoever wants to know about added clients
     *
     * @param rouplexTcpClientConnectedListener
     */
    public void setRouplexTcpClientConnectedListener(@Nullable NotificationListener<RouplexTcpClient> rouplexTcpClientConnectedListener) {
        synchronized (lock) {
            if (this.rouplexTcpClientConnectedListener != null) {
                throw new IllegalStateException("RouplexTcpClientConnectedListener already set.");
            }

            this.rouplexTcpClientConnectedListener = rouplexTcpClientConnectedListener;
        }
    }

    /**
     * Whoever wants to know about removed clients
     *
     * @param rouplexTcpClientClosedListener
     */
    public void setRouplexTcpClientClosedListener(@Nullable NotificationListener<RouplexTcpClient> rouplexTcpClientClosedListener) {
        synchronized (lock) {
            if (this.rouplexTcpClientClosedListener != null) {
                throw new IllegalStateException("RouplexTcpClientClosedListener already set.");
            }

            this.rouplexTcpClientClosedListener = rouplexTcpClientClosedListener;
        }
    }
}
