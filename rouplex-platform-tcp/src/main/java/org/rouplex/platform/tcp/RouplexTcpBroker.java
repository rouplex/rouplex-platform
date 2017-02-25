package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.commons.collections.SortedByValueMap;
import org.rouplex.platform.rr.EventListener;
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
public class RouplexTcpBroker implements Closeable {
    protected final Object lock = new Object();

    protected final Selector selector;
    protected final ExecutorService executorService;
    protected final boolean sharedExecutorService;

    protected final List<RouplexTcpChannel> registeringChannels = new ArrayList<RouplexTcpChannel>();
    protected final Map<SelectionKey, Integer> addingInterestOps = new HashMap<SelectionKey, Integer>();
    protected final Map<SelectionKey, Integer> removingInterestOps = new HashMap<SelectionKey, Integer>();
    protected final SortedByValueMap<SelectionKey, Long> resumingReads = new SortedByValueMap<SelectionKey, Long>();
    protected final SortedByValueMap<SelectionKey, Long> resumingAccepts = new SortedByValueMap<SelectionKey, Long>();

    @Nullable protected EventListener<RouplexTcpClient> tcpClientAddedListener;
    private boolean isClosed;

    public RouplexTcpBroker(Selector selector, ExecutorService executorService) {
        this.selector = selector; // this will be a little trickier with ssl, punting for now
        this.executorService = (sharedExecutorService = executorService != null)
                ? executorService : Executors.newSingleThreadExecutor();
        start();
    }

    void addRouplexChannel(RouplexTcpChannel rouplexTcpChannel) throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IOException("Already closed.");
            }

            registeringChannels.add(rouplexTcpChannel);
            selector.wakeup();
        }
    }

    private void registerRouplexChannels() {
        for (RouplexTcpChannel rouplexTcpChannel : registeringChannels) {
            try {
                SelectableChannel selectableChannel = rouplexTcpChannel.getSelectableChannel();
                selectableChannel.configureBlocking(false);

                int interestOps;
                if (rouplexTcpChannel instanceof RouplexTcpClient) {
                    interestOps = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
                    if (!((SocketChannel) selectableChannel).isConnected()) {
                        interestOps |= SelectionKey.OP_CONNECT;
                    }
                } else if (rouplexTcpChannel instanceof RouplexTcpServer) {
                    interestOps = SelectionKey.OP_ACCEPT;
                } else {
                    continue; // internal implementation error
                }

                rouplexTcpChannel.setSelectionKey(selectableChannel.register(selector, interestOps, rouplexTcpChannel));

                // add rate client creation rate limiting here
                if (interestOps != SelectionKey.OP_ACCEPT && tcpClientAddedListener != null) {
                    tcpClientAddedListener.onEvent((RouplexTcpClient) rouplexTcpChannel);
                }
            } catch (IOException cce) {
                try {
                    rouplexTcpChannel.close();
                } catch (IOException ioe) {
                    // we could add an exceptions consumer in the future and sink this out
                }
            }
        }

        registeringChannels.clear();
    }

    private long updateInterestOpsAndCalculateSelectTimeout() {
        for (Map.Entry<SelectionKey, Integer> addingInterestOp : addingInterestOps.entrySet()) {
            addingInterestOp.getKey().interestOps(
                    addingInterestOp.getKey().interestOps() | addingInterestOp.getValue());
        }

        for (Map.Entry<SelectionKey, Integer> removingInterestOp : removingInterestOps.entrySet()) {
            removingInterestOp.getKey().interestOps(
                    removingInterestOp.getKey().interestOps() & ~removingInterestOp.getValue());
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
            if (resumingOp.getValue() <= now) {
                resumingOp.getKey().interestOps(resumingOp.getKey().interestOps() | op);
                resumingSelectors.remove(resumingOp.getKey());
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
                currentThread.setName("RouplexTcpBroker");
                ByteBuffer readBuffer = ByteBuffer.allocate(1000000);

                while (!isClosed()) {
                    if (executorService.isShutdown() || currentThread.isInterrupted()) {
                        isClosed = true;
                    }

                    long selectTimeout;
                    synchronized (lock) {
                        registerRouplexChannels();
                        selectTimeout = updateInterestOpsAndCalculateSelectTimeout();
                    }

                    try {
                        selector.selectedKeys().clear();
                        selector.select(selectTimeout);

                        for (SelectionKey selectionKey : selector.selectedKeys()) {
                            try {
                                if (selectionKey.isAcceptable()) {
                                    SocketChannel socketChannel = ((ServerSocketChannel) selectionKey.channel()).accept();
                                    addRouplexChannel(new RouplexTcpClient(socketChannel, RouplexTcpBroker.this));
                                    continue;
                                }

                                SocketChannel socketChannel = ((SocketChannel) selectionKey.channel());
                                RouplexTcpClient rouplexTcpClient = (RouplexTcpClient) selectionKey.attachment();

                                if (selectionKey.isReadable()) {
                                    int read = socketChannel.read(readBuffer);
                                    byte[] readPayload;

                                    switch (read) {
                                        case -1:
                                            readPayload = null;
                                            break;
                                        case 0:
                                            readPayload = new byte[0];
                                            break;
                                        default:
                                            readPayload = new byte[read];
                                            readBuffer.flip();
                                            readBuffer.get(readPayload);
                                            readBuffer.compact();
                                    }

                                    try {
                                        rouplexTcpClient.throttledReceiver.consumeSocketInput(readPayload);
                                    } catch (RuntimeException receiverException) {
                                        readPayload = null; // force client close
                                    }

                                    if (readPayload == null) {
                                        rouplexTcpClient.close();
                                        continue;
                                    }
                                }

                                if (selectionKey.isWritable()) {
                                    while (true) {
                                        ByteBuffer writeBuffer = rouplexTcpClient.throttledSender.pollFirstWriteBuffer();

                                        if (writeBuffer == null) { // nothing in the queue
                                            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                                            break;
                                        }

                                        if (!writeBuffer.hasRemaining()) { // empty buffer is "marker for EOS"
                                            socketChannel.shutdownOutput();
                                            break;
                                        }

                                        socketChannel.write(writeBuffer);
                                        if (writeBuffer.hasRemaining()) {
                                            break;
                                        }

                                        try {
                                            rouplexTcpClient.throttledSender.removeWriteBuffer(writeBuffer);
                                        } catch (RuntimeException throttleException) {
                                            rouplexTcpClient.close();
                                            break;
                                        }
                                    }
                                }
                            } catch (IOException ioe) {
                                // channel gets closed, unregistered
                            }
                        }
                    } catch (Exception ioe) {
                        //logger.info("Server finished accepting new connections. Cause: " + e.getMessage());
                        try {
                            close();
                        } catch (IOException e2) {
                            //logger.info("Failed stopping server. Cause: " + e2.getMessage());
                        }
                    }
                }
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
    public void close() throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                return;
            }

            isClosed = true;
            IOException pendingException = null;

            for (SelectionKey selectionKey : selector.keys()) {
                try {
                    selectionKey.channel().close();
                } catch (IOException ioe) {
                    if (pendingException == null) {
                        pendingException = ioe;
                    }
                }
            }

            if (!sharedExecutorService) {
                executorService.shutdownNow();
            }

            selector.wakeup();

            if (pendingException != null) {
                throw pendingException;
            }
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return isClosed;
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
    public void setTcpClientAddedListener(@Nullable EventListener<RouplexTcpClient> tcpClientAddedListener) {
        this.tcpClientAddedListener = tcpClientAddedListener;
    }
}
