package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSelector;
import org.rouplex.nio.channels.SSLServerSocketChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpServer extends RouplexTcpEndPoint {
    protected RouplexTcpServerListener rouplexTcpServerListener;
    protected int backlog;

    public static class Builder extends RouplexTcpEndPoint.Builder<RouplexTcpServer, Builder> {
        Builder(RouplexTcpServer instance) {
            super(instance);
        }

        protected void checkCanBuild() {
            if (instance.localAddress == null) {
                throw new IllegalStateException("Missing value for localAddress"); // maybe remove this constraint
            }
        }

        public Builder withServerSocketChannel(ServerSocketChannel serverSocketChannel) {
            checkNotBuilt();

            instance.selectableChannel = serverSocketChannel;
            return builder;
        }

        public Builder withBacklog(int backlog) {
            checkNotBuilt();
            instance.backlog = backlog;
            return builder;
        }

        public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) throws IOException {
            checkNotBuilt();

            try {
                instance.sslContext = secure ? sslContext != null ? sslContext : SSLContext.getDefault() : null;
            } catch (Exception e) {
                throw new IOException("Could not create SSLContext.", e);
            }

            return builder;
        }

        public Builder withRouplexTcpServerListener(RouplexTcpServerListener rouplexTcpServerListener) {
            checkNotBuilt();

            instance.rouplexTcpServerListener = rouplexTcpServerListener;
            return builder;
        }

        @Override
        public RouplexTcpServer buildAsync() throws IOException {
            checkNotBuilt();
            checkCanBuild();

            RouplexTcpServer result = instance;
            instance = null;
            return result.start();
        }
    }

    public static Builder newBuilder() {
        return new Builder(new RouplexTcpServer(null, null));
    }

    RouplexTcpServer(ServerSocketChannel serverSocketChannel, RouplexTcpBinder rouplexTcpBinder) {
        super(serverSocketChannel, rouplexTcpBinder);
    }

    private RouplexTcpServer start() throws IOException {
        if (rouplexTcpBinder == null) {
            rouplexTcpBinder = new RouplexTcpBinder(sslContext == null ? Selector.open() : SSLSelector.open(), null);
        }

        ServerSocketChannel serverSocketChannel;
        if (selectableChannel != null) {
            serverSocketChannel = (ServerSocketChannel) selectableChannel;
        } else {
            serverSocketChannel = sslContext == null ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
            selectableChannel = serverSocketChannel;
        }

        if (sendBufferSize != 0) {
            // hmm interesting, serverSocket doesn't have sendBufferSize
            // serverSocketChannel.socket().setSendBufferSize(sendBufferSize);
        }
        if (receiveBufferSize != 0) {
            serverSocketChannel.socket().setReceiveBufferSize(receiveBufferSize);
        }

        serverSocketChannel.configureBlocking(false);
        if (!serverSocketChannel.socket().isBound()) {
            serverSocketChannel.bind(localAddress, backlog);
        }

        rouplexTcpBinder.asyncRegisterTcpEndPoint(this);
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
