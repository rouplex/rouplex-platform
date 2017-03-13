package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Final;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.platform.rr.NotificationListener;

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
    protected RouplexTcpBinder rouplexTcpBinder;
    @Final
    protected SSLContext sslContext;
    @Final
    protected boolean sharedRouplexBinder;
    @Final // set in build() if not set
    protected SelectableChannel selectableChannel;
    @Final
    protected NotificationListener<RouplexTcpClient> rouplexTcpClientConnectedListener;
    @Final
    protected NotificationListener<RouplexTcpClient> rouplexTcpClientConnectionFailedListener;

    // not final, it is set from binder
    protected SelectionKey selectionKey;
    // not final, set and changed by user
    protected Object attachment;

    protected boolean isClosed;

    RouplexTcpChannel(SelectableChannel selectableChannel, RouplexTcpBinder rouplexTcpBinder) {
        this.selectableChannel = selectableChannel;
        this.rouplexTcpBinder = rouplexTcpBinder;
        sharedRouplexBinder = true;
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

        public B withRouplexTcpBinder(RouplexTcpBinder rouplexTcpBinder) {
            checkNotBuilt();

            instance.rouplexTcpBinder = rouplexTcpBinder;
            instance.sharedRouplexBinder = rouplexTcpBinder != null;
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

        public B withRouplexTcpClientConnectedListener(
                NotificationListener<RouplexTcpClient> rouplexTcpClientConnectedListener) {
            checkNotBuilt();

            instance.rouplexTcpClientConnectedListener = rouplexTcpClientConnectedListener;
            return builder;
        }

        public B withRouplexTcpClientConnectionFailedListener(
                NotificationListener<RouplexTcpClient> rouplexTcpClientConnectionFailedListener) {
            checkNotBuilt();

            instance.rouplexTcpClientConnectionFailedListener = rouplexTcpClientConnectionFailedListener;
            return builder;
        }
        public B withAttachment(@Nullable Object attachment) {
            instance.attachment = attachment;
            return builder;
        }
    }

    public RouplexTcpBinder getRouplexTcpBinder() {
        return rouplexTcpBinder;
    }

    public SocketAddress getLocalAddress() throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IOException("Already closed");
            }

            return selectableChannel == null ? localAddress : ((ServerSocketChannel) selectableChannel).getLocalAddress();
        }
    }

    /**
     * This method is called from {@link RouplexTcpBinder} when the binder registers this channel.
     *
     * @param selectionKey
     */
    void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    SelectableChannel getSelectableChannel() {
        synchronized (lock) {
            return selectableChannel;
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                return;
            }

            isClosed = true;
            IOException pendingIOException = null;

            try {
                selectableChannel.close();
            } catch (IOException ioe) {
                pendingIOException = ioe;
            }

            if (rouplexTcpBinder != null) {
                try {
                    rouplexTcpBinder.asyncNotifyClosedTcpChannel(this);
                } catch (IOException ioe) {
                    if (pendingIOException == null) {
                        pendingIOException = ioe;
                    }
                }

                if (!sharedRouplexBinder) {
                    rouplexTcpBinder.close();
                }
            }

            if (pendingIOException != null) {
                throw pendingIOException;
            }
        }
    }

    void closeSilently() {
        try {
            close();
        } catch (IOException ioe) {
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return isClosed;
        }
    }

    public Object getAttachment() {
        return attachment;
    }

    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }
}
