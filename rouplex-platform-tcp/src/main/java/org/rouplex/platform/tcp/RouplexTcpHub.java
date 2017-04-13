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
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RouplexTcpHub implements Closeable {
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
    protected RouplexTcpConnectorLifecycleListener<RouplexTcpClient> rouplexTcpClientLifecycleListener;
    @Final
    protected int sendBufferSize;
    @Final
    protected int receiveBufferSize;

    // not final, it is set from binder
    protected SelectionKey selectionKey;
    // not final, set and changed by user
    protected Object attachment;

    protected boolean isClosed;

    RouplexTcpHub(SelectableChannel selectableChannel, RouplexTcpBinder rouplexTcpBinder) {
        this.selectableChannel = selectableChannel;
        this.rouplexTcpBinder = rouplexTcpBinder;
        sharedRouplexBinder = true;
    }

    static abstract class Builder<T extends RouplexTcpHub, B extends Builder> {
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

        public B withSendBufferSize(int sendBufferSize) {
            checkNotBuilt();

            instance.sendBufferSize = sendBufferSize;
            return builder;
        }

        public B withReceiveBufferSize(int receiveBufferSize) {
            checkNotBuilt();

            instance.receiveBufferSize = receiveBufferSize;
            return builder;
        }

        public B withRouplexTcpClientLifecycleListener(
                RouplexTcpConnectorLifecycleListener<RouplexTcpClient> rouplexTcpClientLifecycleListener) {
            checkNotBuilt();

            instance.rouplexTcpClientLifecycleListener = rouplexTcpClientLifecycleListener;
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

    public SocketAddress getLocalAddress(boolean resolved) throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IOException("Already closed");
            }

            SocketAddress result = selectableChannel != null
                    ? ((NetworkChannel) selectableChannel).getLocalAddress() : null;

            if (result != null) {
                return result;
            }

            if (resolved) {
                throw new IOException("Not bound yet");
            }

            return localAddress;
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
