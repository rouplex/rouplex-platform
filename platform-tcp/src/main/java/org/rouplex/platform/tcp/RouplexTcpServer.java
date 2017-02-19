package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLServerSocketChannel;
import org.rouplex.nio.channels.spi.SSLSelector;
import org.rouplex.platform.Reply;
import org.rouplex.platform.RequestHandler;
import org.rouplex.platform.RouplexBinder;
import org.rouplex.platform.RouplexService;

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

    protected InetSocketAddress localAddress;
    protected SSLContext sslContext;
    protected int logback;

    protected Selector selector;
    protected ServerSocketChannel serverSocketChannel;
    protected ExecutorService serverExecutor = Executors.newSingleThreadExecutor();

    Set<SelectionKey> pendingReadRegistration = new HashSet<SelectionKey>();
    Set<SelectionKey> pendingWriteRegistration = new HashSet<SelectionKey>();

    RequestHandler<byte[], ByteBuffer> requestHandler;

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

    public RouplexTcpServer withLogback(int logback) throws Exception {
        checkCanConfigure();

        this.logback = logback;
        return this;
    }

    public RouplexTcpServer withBoundProvider(RequestHandler<byte[], ByteBuffer> requestHandler) throws Exception {
        checkCanConfigure();

        this.requestHandler = requestHandler;
        return this;
    }

    @Override
    public void bindServiceProvider(RouplexService provider) {
        this.requestHandler = (RequestHandler<byte[], ByteBuffer>) provider;
    }

    public RouplexTcpServer start() throws IOException {
        checkCanStart();
        checkCanConfigure();

        selector = sslContext == null ? Selector.open() : SSLSelector.open();
        serverSocketChannel = sslContext == null ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        serverSocketChannel.bind(localAddress, logback);

        serverExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.currentThread().setName("RouplexTcpServer");
                    ByteBuffer readBuffer = ByteBuffer.allocate(1000000);

                    while (!serverExecutor.isShutdown()) {
                        selector.selectedKeys().clear();
                        selector.select();

                        for (SelectionKey selectionKey : pendingReadRegistration) {
                            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
                        }

                        for (SelectionKey selectionKey : pendingWriteRegistration) {
                            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                        }

                        for (SelectionKey selectionKey : selector.selectedKeys()) {
                            try {
                                if (selectionKey.isAcceptable()) {
                                    SocketChannel channel = serverSocketChannel.accept();
                                    channel.configureBlocking(false);

//                                channels.put(channel, new ChannelQueue(channel));
                                    SelectionKey sk = channel.register(selector, SelectionKey.OP_READ);
                                    sk.attach(new ChannelQueue(RouplexTcpServer.this, sk));
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

                                        if (reply == null) {
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
                }

                try {
                    close();
                } catch (IOException e2) {
                    //logger.info("Failed stopping server. Cause: " + e2.getMessage());
                }
            }
        });

        return this;
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
        serverExecutor.shutdownNow();

        try {
            selector.close();
        } catch (IOException ioe) {
            //
        }

        try {
            serverSocketChannel.close();
        } catch (IOException ioe) {
            //
        }
    }
}
