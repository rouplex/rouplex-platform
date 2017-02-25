package org.rouplex.platform.tcp;

import org.rouplex.nio.channels.SSLServerSocketChannel;
import org.rouplex.nio.channels.spi.SSLSelector;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.Executors;

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
        if (rouplexTcpBroker == null) {
            rouplexTcpBroker = new RouplexTcpBroker(sslContext == null
                    ? Selector.open() : SSLSelector.open(), Executors.newSingleThreadExecutor());
        }

        ServerSocketChannel serverSocketChannel = sslContext == null ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
        serverSocketChannel.bind(localAddress, backlog);
        selectableChannel = serverSocketChannel;
        rouplexTcpBroker.addRouplexChannel(this);

        return this;
    }
}
