package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLServerSocketChannel;
import org.rouplex.nio.channels.spi.SSLSelector;

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

        public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) throws Exception {
            checkNotBuilt();

            instance.sslContext = secure ? sslContext != null ? sslContext : SSLContext.getDefault() : null;
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
        return new Builder(new RouplexTcpServer());
    }

    RouplexTcpServer() {
        super(null, null);
    }

    private RouplexTcpServer start() throws IOException {
        if (rouplexTcpBinder == null) {
            rouplexTcpBinder = new RouplexTcpBinder(sslContext == null ? Selector.open() : SSLSelector.open(), null);
        }

        ServerSocketChannel serverSocketChannel = sslContext == null ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
        serverSocketChannel.bind(localAddress, backlog);
        selectableChannel = serverSocketChannel;
        rouplexTcpBinder.addRouplexChannel(this);

        return this;
    }
}
