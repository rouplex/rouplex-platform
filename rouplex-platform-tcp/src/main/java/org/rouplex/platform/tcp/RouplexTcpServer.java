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
public class RouplexTcpServer extends RouplexTcpChannel {
    protected int backlog;

    public static class Builder extends RouplexTcpChannel.Builder<RouplexTcpServer, Builder> {
        Builder(RouplexTcpServer instance) {
            super(instance);
        }

        protected void checkCanBuild() {
            if (instance.localAddress == null) {
                throw new IllegalStateException("Missing value for localAddress");
            }
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

        @Override
        public RouplexTcpServer build() throws IOException {
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

    public static RouplexTcpServer wrap(ServerSocketChannel serverSocketChannel) throws IOException {
        return wrap(serverSocketChannel, null);
    }

    public static RouplexTcpServer wrap(ServerSocketChannel serverSocketChannel, RouplexTcpBinder rouplexTcpBinder) throws IOException {
        RouplexTcpServer result = new RouplexTcpServer(serverSocketChannel, rouplexTcpBinder);
        result.start();
        return result;
    }

    RouplexTcpServer(ServerSocketChannel serverSocketChannel, RouplexTcpBinder rouplexTcpBinder) {
        super(serverSocketChannel, rouplexTcpBinder);
    }

    private RouplexTcpServer start() throws IOException {
        if (rouplexTcpBinder == null) {
            rouplexTcpBinder = new RouplexTcpBinder(sslContext == null ? Selector.open() : SSLSelector.open(), null);
        }

        ServerSocketChannel serverSocketChannel = selectableChannel != null ? (ServerSocketChannel) selectableChannel :
                sslContext == null ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);

        if (sendBufferSize != 0) {
            // hmm interesting, serverSocket doesn't have sendBufferSize
            // serverSocketChannel.socket().setSendBufferSize(sendBufferSize);
        }
        if (receiveBufferSize != 0) {
            serverSocketChannel.socket().setReceiveBufferSize(receiveBufferSize);
        }

        selectableChannel = serverSocketChannel;
        serverSocketChannel.bind(localAddress, backlog);
        rouplexTcpBinder.asyncRegisterTcpChannel(this);

        return this;
    }
}
