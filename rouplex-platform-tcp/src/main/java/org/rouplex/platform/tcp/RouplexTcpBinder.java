package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.commons.collections.SortedByValueMap;
import org.rouplex.platform.rr.NotificationListener;
import org.rouplex.platform.rr.Throttle;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpBinder implements Closeable {
    protected final static byte[] EOS = new byte[0];
    protected final Object lock = new Object();

    protected final Selector selector;
    protected final ExecutorService executorService;
    protected final boolean sharedExecutorService;

    protected List<RouplexTcpChannel> addingChannels = new ArrayList<RouplexTcpChannel>();
    protected List<RouplexTcpChannel> removingChannels = new ArrayList<RouplexTcpChannel>();
    protected final Map<SelectionKey, Integer> addingInterestOps = new HashMap<SelectionKey, Integer>();
    protected final Map<SelectionKey, Integer> removingInterestOps = new HashMap<SelectionKey, Integer>();
    protected final SortedByValueMap<SelectionKey, Long> resumingReads = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingAccepts = new SortedByValueMap<SelectionKey, Long>();

    @Nullable
    protected NotificationListener<RouplexTcpClient> tcpClientAddedListener;
    @Nullable
    protected NotificationListener<RouplexTcpClient> tcpClientRemovedListener;

    // or isClosed, same semantics
    private boolean isClosing;

    public RouplexTcpBinder(Selector selector) {
        this(selector, null);
    }

    public RouplexTcpBinder(Selector selector, ExecutorService executorService) {
        this.selector = selector; // this will be a little trickier with ssl, punting for now
        this.executorService = (sharedExecutorService = executorService != null)
                ? executorService : Executors.newCachedThreadPool();
        start();
    }

    void addChannel(RouplexTcpChannel channel) throws IOException {
        synchronized (lock) {
            if (isClosing) {
                throw new IOException("Already closed.");
            }

            addingChannels.add(channel);
            selector.wakeup();
        }
    }

    private void registerChannels(List<RouplexTcpChannel> registerChannels) {
        for (RouplexTcpChannel registerChannel : registerChannels) {
            try {
                SelectableChannel selectableChannel = registerChannel.getSelectableChannel();
                selectableChannel.configureBlocking(false);

                int interestOps;
                if (registerChannel instanceof RouplexTcpClient) {
                    interestOps = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
                    if (!((SocketChannel) selectableChannel).isConnected()) {
                        interestOps |= SelectionKey.OP_CONNECT;
                    }
                } else if (registerChannel instanceof RouplexTcpServer) {
                    interestOps = SelectionKey.OP_ACCEPT;
                } else {
                    continue; // internal implementation error
                }

                registerChannel.setSelectionKey(selectableChannel.register(selector, interestOps, registerChannel));

                // add rate client creation rate limiting here
                if (interestOps != SelectionKey.OP_ACCEPT && tcpClientAddedListener != null) {
                    tcpClientAddedListener.onEvent((RouplexTcpClient) registerChannel);
                }
            } catch (Exception cce) {
                // ClosedChannelException | IllegalBlockingModeException | RuntimeException from client.onEvent()
                try {
                    registerChannel.close();
                } catch (IOException ioe) {
                    // we could add an exceptions consumer in the future and sink this out
                }
            }
        }
    }

    // For channels that closed and need to report via binder
    void removeChannel(RouplexTcpChannel channel) throws IOException {
        synchronized (lock) {
            if (isClosing) {
                throw new IOException("Already closed.");
            }

            removingChannels.add(channel);
            selector.wakeup();
        }
    }

    // For channels
    void closeChannel(final RouplexTcpChannel channel) {
        // this is called internally and isClosing can never be null
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    channel.close();
                } catch (IOException ioe) {
                    //
                }
            }
        });
    }

    private void removeChannels(List<RouplexTcpChannel> closedChannels) {
        for (RouplexTcpChannel closedChannel : closedChannels) {
            try {
                if (tcpClientRemovedListener != null) {
                    tcpClientRemovedListener.onEvent((RouplexTcpClient) closedChannel);
                }
            } catch (Exception cce) {
                // channel is already closed
            }
        }
    }

    private long updateInterestOpsAndCalculateSelectTimeout() {
        for (Map.Entry<SelectionKey, Integer> addingInterestOp : addingInterestOps.entrySet()) {
            SelectionKey selectionKey = addingInterestOp.getKey();
            if (selectionKey.isValid()) { // channel may have closed during the preceding loop
                selectionKey.interestOps(selectionKey.interestOps() | addingInterestOp.getValue());
            }
        }

        for (Map.Entry<SelectionKey, Integer> removingInterestOp : removingInterestOps.entrySet()) {
            SelectionKey selectionKey = removingInterestOp.getKey();// channel may have closed during the preceding loop
            if (selectionKey.isValid()) { // channel may have closed during the preceding loop
                selectionKey.interestOps(selectionKey.interestOps() & ~removingInterestOp.getValue());
            }
        }

        addingInterestOps.clear();
        removingInterestOps.clear();

        long now = System.currentTimeMillis();
        return Math.min(
                calculateNextSelectTimeout(resumingReads, now, SelectionKey.OP_READ),
                calculateNextSelectTimeout(resumingAccepts, now, SelectionKey.OP_ACCEPT)
        );
    }

    private long calculateNextSelectTimeout(SortedByValueMap<SelectionKey, Long> resumingSelectors, long now, int op) {
        long selectTimeout = 0;
        for (Map.Entry<SelectionKey, Long> resumingOp : resumingSelectors.sortedByValue()) {
            SelectionKey selectionKey = resumingOp.getKey();// channel may have closed during the preceding loop
            if (!selectionKey.isValid()) {
                resumingSelectors.remove(selectionKey);
                continue;
            }

            if (resumingOp.getValue() <= now) {
                selectionKey.interestOps(selectionKey.interestOps() | op);
                resumingSelectors.remove(selectionKey);
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
                ByteBuffer readBuffer = ByteBuffer.allocate(1000000);

                try {
                    while (true) {
                        if (executorService.isShutdown() || currentThread.isInterrupted()) {
                            close();
                        }

                        long selectTimeout;
                        List<RouplexTcpChannel> registerChannels;
                        List<RouplexTcpChannel> closedChannels;
                        synchronized (lock) {
                            if (isClosing) {
                                break;
                            }

                            if (addingChannels.isEmpty()) {
                                registerChannels = null;
                            } else {
                                registerChannels = addingChannels;
                                addingChannels = new ArrayList<RouplexTcpChannel>();
                            }

                            if (removingChannels.isEmpty()) {
                                closedChannels = null;
                            } else {
                                closedChannels = removingChannels;
                                removingChannels = new ArrayList<RouplexTcpChannel>();
                            }

                            selectTimeout = updateInterestOpsAndCalculateSelectTimeout();
                        }

                        if (registerChannels != null) {
                            // fire only lock-free events towards client! The trade off is that a new channel may fire
                            // after a close() call, but that same chennel will be closed shortly after anyway
                            registerChannels(registerChannels);
                        }

                        if (closedChannels != null) {
                            removeChannels(closedChannels);
                        }

                        selector.selectedKeys().clear();
                        selector.select(selectTimeout);

                        for (SelectionKey selectionKey : selector.selectedKeys()) {
                            try {
                                if (selectionKey.isAcceptable()) {
                                    SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
                                    addChannel(new RouplexTcpClient(socketChannel, RouplexTcpBinder.this,
                                            (RouplexTcpServer) selectionKey.attachment()));
                                    continue;
                                }

                                SocketChannel socketChannel = ((SocketChannel) selectionKey.channel());
                                RouplexTcpClient rouplexTcpClient = (RouplexTcpClient) selectionKey.attachment();

                                if (selectionKey.isReadable()) {
                                    int read = socketChannel.read(readBuffer);

                                    if (read != 0) {
                                        byte[] readPayload;

                                        switch (read) {
                                            case -1:
                                                readPayload = EOS;
                                                break;
                                            default:
                                                readPayload = new byte[read];
                                                readBuffer.flip();
                                                readBuffer.get(readPayload);
                                                readBuffer.compact();
                                        }

                                        try {
                                            if (!rouplexTcpClient.throttledReceiver.consumeSocketInput(readPayload) || read == -1) {
                                                selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
                                            }
                                        } catch (RuntimeException receiverException) {
                                            closeChannel(rouplexTcpClient); // async close, this may block
                                            continue;
                                        }
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
                                        if (eos) {
                                            socketChannel.shutdownOutput();
                                        } else {
                                            socketChannel.write(writeBuffer);
                                            if (writeBuffer.hasRemaining()) {
                                                break;
                                            }
                                        }

                                        try {
                                            rouplexTcpClient.throttledSender.removeWriteBuffer(writeBuffer);
                                        } catch (RuntimeException throttleException) {
                                            closeChannel(rouplexTcpClient); // async close, this may block
                                            break;
                                        }

                                        if (eos) {
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                // channel gets closed, unregistered
                            }
                        }
                    }
                } catch (Exception ioe) {
                    ioe.printStackTrace();
                    // something major, close the binder
                }

                syncClose(); // close synchronously.
            }
        });
    }

    void addInterestOps(SelectionKey selectionKey, int interestOps) {
        synchronized (lock) {
            Integer alreadyAddingInterestOps = addingInterestOps.put(selectionKey, interestOps);
            if (alreadyAddingInterestOps != null) {
                addingInterestOps.put(selectionKey, alreadyAddingInterestOps | interestOps);
            }
        }

        selector.wakeup();
    }

    void removeInterestOps(SelectionKey selectionKey, int interestOps) {
        synchronized (lock) {
            Integer alreadyRemovingInterestOps = removingInterestOps.put(selectionKey, interestOps);
            if (alreadyRemovingInterestOps != null) {
                removingInterestOps.put(selectionKey, alreadyRemovingInterestOps | interestOps);
            }
        }

        selector.wakeup();
    }

    void pauseRead(SelectionKey selectionKey, long resumeTimestamp) {
        synchronized (lock) {
            resumingReads.put(selectionKey, resumeTimestamp);
            removeInterestOps(selectionKey, SelectionKey.OP_READ);
        }
    }

    void pauseAccept(SelectionKey selectionKey, long resumeTimestamp) {
        synchronized (lock) {
            resumingAccepts.put(selectionKey, resumeTimestamp);
            removeInterestOps(selectionKey, SelectionKey.OP_ACCEPT);
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

        for (RouplexTcpChannel rouplexTcpChannel : addingChannels) {
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

    Throttle throttle;
    public Throttle getTcpClientAcceptThrottle() {
        return throttle;
    }

    /**
     * Whoever wants to know about added clients
     *
     * @param tcpClientAddedListener
     */
    public void setRouplexTcpClientAddedListener(@Nullable NotificationListener<RouplexTcpClient> tcpClientAddedListener) {
        synchronized (lock) {
            if (this.tcpClientAddedListener != null) {
                throw new IllegalStateException("RouplexTcpClientAddedListener already set.");
            }

            this.tcpClientAddedListener = tcpClientAddedListener;
        }
    }

    /**
     * Whoever wants to know about removed clients
     *
     * @param tcpClientRemovedListener
     */
    public void setRouplexTcpClientRemovedListener(@Nullable NotificationListener<RouplexTcpClient> tcpClientRemovedListener) {
        synchronized (lock) {
            if (this.tcpClientRemovedListener != null) {
                throw new IllegalStateException("RouplexTcpClientRemovedListener already set.");
            }

            this.tcpClientRemovedListener = tcpClientRemovedListener;
        }
    }
}
