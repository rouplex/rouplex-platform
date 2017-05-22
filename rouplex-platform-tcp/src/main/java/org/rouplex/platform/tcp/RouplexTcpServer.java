package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLServerSocketChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.channels.ServerSocketChannel;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpServer extends RouplexTcpEndPoint {
    public static class Builder extends RouplexTcpEndPoint.Builder<RouplexTcpServer, Builder> {
        protected int backlog;
        protected RouplexTcpServerListener rouplexTcpServerListener;

        protected void checkCanBuild() {
            if (localAddress == null) {
                throw new IllegalStateException("Missing value for localAddress"); // maybe remove this constraint
            }
        }

        public Builder withServerSocketChannel(ServerSocketChannel serverSocketChannel) {
            checkNotBuilt();

            this.selectableChannel = serverSocketChannel;
            return builder;
        }

        public Builder withBacklog(int backlog) {
            checkNotBuilt();

            this.backlog = backlog;
            return builder;
        }

        public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) throws IOException {
            checkNotBuilt();

            try {
                this.sslContext = secure ? sslContext != null ? sslContext : SSLContext.getDefault() : null;
            } catch (Exception e) {
                throw new IOException("Could not create SSLContext.", e);
            }

            return builder;
        }

        public Builder withRouplexTcpServerListener(RouplexTcpServerListener rouplexTcpServerListener) {
            checkNotBuilt();

            this.rouplexTcpServerListener = rouplexTcpServerListener;
            return builder;
        }

        @Override
        public RouplexTcpServer buildAsync() throws IOException {
            checkNotBuilt();
            checkCanBuild();

            if (selectableChannel == null) {
                selectableChannel = sslContext == null
                        ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
            }

            RouplexTcpServer result = new RouplexTcpServer(this);
            builder = null;
            return result.start();
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    protected final int sendBufferSize;
    protected final int receiveBufferSize;
    protected final RouplexTcpServerListener rouplexTcpServerListener;

    RouplexTcpServer(Builder builder) {
        super(builder);

        this.sendBufferSize = builder.sendBufferSize;
        this.receiveBufferSize = builder.receiveBufferSize;
        this.rouplexTcpServerListener = builder.rouplexTcpServerListener;
    }

    private RouplexTcpServer start() throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectableChannel;

        if (builder.receiveBufferSize != 0) {
            // serverSocket doesn't have sendBufferSize, and receiveBufferSize is not honored (at least in macosx
            // where I am developing/testing), we set this value on individually accepted sockets again anyway
            serverSocketChannel.socket().setReceiveBufferSize(builder.receiveBufferSize);
        }

        serverSocketChannel.configureBlocking(false);
        if (!serverSocketChannel.socket().isBound()) {
            serverSocketChannel.bind(builder.localAddress, ((Builder) builder).backlog);
        }

        rouplexTcpSelector.asyncRegisterTcpEndPoint(this);
        return this;
    }

    void handleBound() {
        updateOpen(null);

        if (rouplexTcpServerListener != null) {
            rouplexTcpServerListener.onBound(this);
        }
    }

    void handleUnBound() {
        if (rouplexTcpServerListener != null) {
            rouplexTcpServerListener.onUnBound(this);
        }
    }
}
