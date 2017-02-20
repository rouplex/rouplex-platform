package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLServerSocketChannel;
import org.rouplex.nio.channels.spi.SSLSelector;
import org.rouplex.platform.RouplexBinder;
import org.rouplex.platform.RouplexService;
import org.rouplex.platform.rr.AsyncRepliesService;
import org.rouplex.platform.rr.AsyncReplyService;
import org.rouplex.platform.rr.Reply;
import org.rouplex.platform.rr.SyncReplyService;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpServer implements RouplexBinder, Closeable {

    protected RouplexService serviceProvider;
    protected ExecutorService executorService;
    protected InetSocketAddress localAddress;
    protected SSLContext sslContext;
    protected int logback;

    protected Selector selector; // if null the server is not started
    protected ServerSocketChannel serverSocketChannel; // if null the server is closed

    protected final Set<SelectionKey> pendingReadRegistration = new HashSet<SelectionKey>();
    protected final Set<SelectionKey> pendingWriteRegistration = new HashSet<SelectionKey>();
    protected final Set<SocketChannel> socketChannels = new HashSet<SocketChannel>();

    protected void checkCanConfigure() {
        if (selector != null) {
            throw new IllegalStateException("RouplexTcpServer is already started and cannot change anymore");
        }
    }

    protected void checkCanStart() {
        if (localAddress == null) {
            throw new IllegalStateException(
                    "Please define the [localAddress] in order to start the RouplexTcpServer");
        }
    }

    public RouplexTcpServer withExecutorService(ExecutorService executorService) {
        checkCanConfigure();

        this.executorService = executorService;
        return this;
    }

    public RouplexTcpServer withLocalAddress(InetSocketAddress localAddress) {
        checkCanConfigure();

        this.localAddress = localAddress;
        return this;
    }

    public RouplexTcpServer withLocalAddress(@Nullable String hostname, int port) {
        checkCanConfigure();

        if (hostname == null || hostname.length() == 0) {
            try {
                hostname = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                hostname = "localhost";
            }
        }

        this.localAddress = new InetSocketAddress(hostname, port);
        return this;
    }

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public RouplexTcpServer withSecure(boolean secure, SSLContext sslContext) throws Exception {
        checkCanConfigure();

        this.sslContext = secure ? sslContext != null ? sslContext :  SSLContext.getDefault() : null;
        return this;
    }

    public RouplexTcpServer withLogback(int logback) {
        checkCanConfigure();

        this.logback = logback;
        return this;
    }

    public RouplexTcpServer withServiceProvider(SyncReplyService<byte[], ByteBuffer> serviceProvider) {
        checkCanConfigure();

        this.serviceProvider = serviceProvider;
        return this;
    }

    public RouplexTcpServer withServiceProvider(AsyncReplyService<byte[], ByteBuffer> serviceProvider) {
        checkCanConfigure();

        this.serviceProvider = serviceProvider;
        return this;
    }

    public RouplexTcpServer withServiceProvider(AsyncRepliesService<byte[], ByteBuffer> serviceProvider) {
        checkCanConfigure();

        this.serviceProvider = serviceProvider;
        return this;
    }

    @Override
    public void bindServiceProvider(RouplexService serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    public RouplexTcpServer start() throws IOException {
        checkCanStart();
        checkCanConfigure();

        selector = sslContext == null ? Selector.open() : SSLSelector.open();
        serverSocketChannel = sslContext == null ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        serverSocketChannel.bind(localAddress, logback);

        final ExecutorService es = executorService != null ? executorService : Executors.newFixedThreadPool(1);
        es.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread currentThread = Thread.currentThread();
                    currentThread.setName("RouplexTcpServer");
                    ByteBuffer readBuffer = ByteBuffer.allocate(1000000);

                    while (serverSocketChannel != null && !es.isShutdown() && !currentThread.isInterrupted()) {
                        selector.selectedKeys().clear();
                        selector.select();

                        synchronized (pendingReadRegistration) {
                            for (SelectionKey selectionKey : pendingReadRegistration) {
                                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
                            }

                            pendingReadRegistration.clear();
                        }

                        synchronized (pendingWriteRegistration) {
                            for (SelectionKey selectionKey : pendingWriteRegistration) {
                                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                            }

                            pendingWriteRegistration.clear();
                        }

                        for (SelectionKey selectionKey : selector.selectedKeys()) {
                            try {
                                if (selectionKey.isAcceptable()) {
                                    SocketChannel channel = serverSocketChannel.accept();
                                    channel.configureBlocking(false);

                                    SelectionKey sk = channel.register(selector, SelectionKey.OP_READ);
                                    sk.attach(new ChannelQueue(RouplexTcpServer.this, sk));

                                    addSocketChannel(channel);
                                    continue;
                                }

                                SocketChannel socketChannel = ((SocketChannel) selectionKey.channel());
                                ChannelQueue queue = (ChannelQueue) selectionKey.attachment();

                                if (selectionKey.isReadable()) {
                                    int read = socketChannel.read(readBuffer);
                                    byte[] request;

                                    switch (read) {
                                        case -1:
                                            request = null;
                                            break;
                                        case 0:
                                            request = new byte[0];
                                            break;
                                        default:
                                            request = new byte[read];
                                            readBuffer.flip();
                                            readBuffer.get(request);
                                            readBuffer.compact();
                                    }

                                    if (!queue.addRequest(request)) {
                                        selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_READ);
                                    }
                                }

                                if (selectionKey.isWritable()) {
                                    while (true) {
                                        Reply<ByteBuffer> reply = queue.pollFirstReply();

                                        if (reply == null) { // nothing in the queue
                                            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                                            break;
                                        }

                                        if (System.currentTimeMillis() < reply.expirationTimestamp) {
                                            socketChannel.write(reply.reply);
                                            if (reply.reply.hasRemaining()) {
                                                break;
                                            }
                                        }

                                        queue.removeReply(reply);
                                    }
                                }
                            } catch (IOException ioe) {
                                // channel gets closed, unregistered
                            }
                        }
                    }
                } catch (Exception e) {
                    //logger.info("Server finished accepting new connections. Cause: " + e.getMessage());

                    if (executorService == null) { // only if the executor is owned by this instance shut it down
                        es.shutdownNow();
                    }

                    try {
                        close();
                    } catch (IOException e2) {
                        //logger.info("Failed stopping server. Cause: " + e2.getMessage());
                    }
                }
            }
        });

        return this;
    }

    private void addSocketChannel(SocketChannel socketChannel) {
        synchronized (socketChannels) {
            if (serverSocketChannel != null) {
                socketChannels.add(socketChannel);
            }
        }
    }

    void addPendingReadRegistration(SelectionKey selectionKey) {
        synchronized (pendingReadRegistration) {
            pendingReadRegistration.add(selectionKey);
        }
        selector.wakeup();
    }

    void addPendingWriteRegistration(SelectionKey selectionKey) {
        synchronized (pendingWriteRegistration) {
            pendingWriteRegistration.add(selectionKey);
        }
        selector.wakeup();
    }

    @Override
    public void close() throws IOException {
        synchronized (socketChannels) {
            if (serverSocketChannel == null) {
                return; // already closed
            }

            IOException pendingException = null;

            try {
                serverSocketChannel.close();
            } catch (IOException ioe) {
                pendingException = ioe;
            }

            serverSocketChannel = null;
            selector.wakeup();

            try {
                selector.close();
            } catch (IOException ioe) {
                if (pendingException == null) {
                    pendingException = ioe;
                }
            }

            for (SocketChannel socketChannel : socketChannels) {
                try {
                    socketChannel.close();
                } catch (IOException ioe) {
                    if (pendingException == null) {
                        pendingException = ioe;
                    }
                }
            }

            if (pendingException != null) {
                throw pendingException;
            }
        }
    }
}
