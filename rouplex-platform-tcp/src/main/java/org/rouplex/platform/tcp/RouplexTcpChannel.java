package org.rouplex.platform.tcp;

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

    protected SocketAddress localAddress;
    protected SelectionKey selectionKey;
    protected SelectableChannel selectableChannel;
    protected RouplexTcpBroker rouplexTcpBroker;
    protected SSLContext sslContext;
    protected boolean sharedRouplexBroker;
    private boolean isClosed;

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

            isClosed = true;
            IOException pendingException = null;

            if (selectableChannel != null) {
                try {
                    selectableChannel.close();
                } catch (IOException ioe) {
                    pendingException = ioe;
                }
            }

            if (!sharedRouplexBroker && rouplexTcpBroker != null) {
                try {
                    rouplexTcpBroker.close();
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

    public boolean isClosed() {
        synchronized (lock) {
            return isClosed;
        }
    }
}
