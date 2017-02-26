package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Final;
import org.rouplex.commons.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RouplexTcpChannel implements Closeable {
    protected final Object lock = new Object();

    @Final
    protected SocketAddress localAddress;
    @Final
    protected RouplexTcpBroker rouplexTcpBroker;
    @Final
    protected SSLContext sslContext;
    @Final
    protected boolean sharedRouplexBroker;
    @Final // set in build() if not set
    protected SelectableChannel selectableChannel;
    // not final, it is set from broker
    protected SelectionKey selectionKey;

    RouplexTcpChannel(SelectableChannel selectableChannel, RouplexTcpBroker rouplexTcpBroker) {
        this.selectableChannel = selectableChannel;
        this.rouplexTcpBroker = rouplexTcpBroker;
        sharedRouplexBroker = rouplexTcpBroker != null;
    }

    static abstract class Builder<T extends RouplexTcpChannel, B extends Builder> {
        T instance;
        B builder;

        Builder(T instance) {
            this.instance = instance;
            builder = (B) this;
        }

        public abstract T build() throws IOException;

        protected void checkNotBuilt() {
            if (instance == null) {
                throw new IllegalStateException("Already built. Create a new builder to build a new instance.");
            }
        }

        public B withRouplexBroker(RouplexTcpBroker rouplexTcpBroker) {
            checkNotBuilt();

            instance.rouplexTcpBroker = rouplexTcpBroker;
            instance.sharedRouplexBroker = rouplexTcpBroker != null;
            return builder;
        }

        public B withSecure(boolean secure, @Nullable SSLContext sslContext) throws Exception {
            checkNotBuilt();

            instance.sslContext = secure ? sslContext != null ? sslContext : SSLContext.getDefault() : null;
            return builder;
        }

        public B withLocalAddress(SocketAddress localAddress) {
            checkNotBuilt();

            instance.localAddress = localAddress;
            return builder;
        }

        public B withLocalAddress(@Nullable String hostname, int port) {
            checkNotBuilt();

            if (hostname == null || hostname.length() == 0) {
                try {
                    hostname = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    hostname = "localhost";
                }
            }

            instance.localAddress = new InetSocketAddress(hostname, port);
            return builder;
        }
    }

    public RouplexTcpBroker getRouplexTcpBroker() {
        return rouplexTcpBroker;
    }

    public SocketAddress getLocalAddress() throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IOException("Already closed");
            }

            return selectableChannel == null ? localAddress : ((ServerSocketChannel) selectableChannel).getLocalAddress();
        }
    }

    void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    SelectableChannel getSelectableChannel() {
        return selectableChannel;
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                return;
            }

            if (!sharedRouplexBroker && rouplexTcpBroker != null) {
                rouplexTcpBroker.close();
            }

            selectableChannel.close();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return !selectableChannel.isOpen();
        }
    }
}
