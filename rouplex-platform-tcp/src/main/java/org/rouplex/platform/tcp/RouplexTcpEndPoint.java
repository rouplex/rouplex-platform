package org.rouplex.platform.tcp;

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

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RouplexTcpEndPoint implements Closeable {
    static abstract class Builder<T extends RouplexTcpEndPoint, B extends Builder> {
        protected SocketAddress localAddress;
        protected RouplexTcpBinder rouplexTcpBinder;
        protected int sendBufferSize;
        protected int receiveBufferSize;
        protected Object attachment;

        protected SSLContext sslContext;
        protected SelectableChannel selectableChannel;

        protected B builder;

        Builder() {
            builder = (B) this;
        }

        public abstract T buildAsync() throws IOException;

        protected void checkNotBuilt() {
            if (builder == null) {
                throw new IllegalStateException("Already built. Create a new builder to build a new instance.");
            }
        }

        public B withRouplexTcpBinder(RouplexTcpBinder rouplexTcpBinder) {
            checkNotBuilt();

            this.rouplexTcpBinder = rouplexTcpBinder;
            return builder;
        }

        public B withLocalAddress(SocketAddress localAddress) {
            checkNotBuilt();

            this.localAddress = localAddress;
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

            this.localAddress = new InetSocketAddress(hostname, port);
            return builder;
        }

        public B withSendBufferSize(int sendBufferSize) {
            checkNotBuilt();

            this.sendBufferSize = sendBufferSize > 0 ? sendBufferSize : 0;
            return builder;
        }

        public B withReceiveBufferSize(int receiveBufferSize) {
            checkNotBuilt();

            this.receiveBufferSize = receiveBufferSize > 0 ? receiveBufferSize : 0;
            return builder;
        }

        public B withAttachment(@Nullable Object attachment) {
            this.attachment = attachment;
            return builder;
        }

        public T build() throws IOException {
            return build(0);
        }

        /**
         * Build the endpoint in a blocking manner.
         *
         * @param timeoutMillis
         *          A non negative value for timeout, in milliseconds. The value 0 is interpreted as blocking
         *          indefinitely until the endpoint is open or fails to open.
         * @return
         *          the built and open RouplexTcpEndPoint
         * @throws IOException
         *          If any exception is thrown during the process of building, or if the timeout has been reached and
         *          the endpoint is not open yet.
         */
        public T build(int timeoutMillis) throws IOException {
            T result = buildAsync();
            builder = null;
            result.waitForOpen(timeoutMillis > 0 ? timeoutMillis : Long.MAX_VALUE);
            return result;
        }
    }

    protected final Builder builder;
    protected final Object lock = new Object();
    protected final boolean sharedRouplexBinder;
    protected final RouplexTcpBinder rouplexTcpBinder;
    protected final RouplexTcpSelector rouplexTcpSelector;
    protected final SelectableChannel selectableChannel;

    // not final, it is set from binder
    protected SelectionKey selectionKey;
    // not final, set and changed by user
    protected Object attachment;

    protected boolean open;
    private boolean closed;

    private IOException ioException;

    protected <T extends RouplexTcpEndPoint, B extends Builder> RouplexTcpEndPoint(Builder<T, B> builder) {
        this.builder = builder;
        this.sharedRouplexBinder = builder.rouplexTcpBinder != null;
        this.rouplexTcpBinder = sharedRouplexBinder ? builder.rouplexTcpBinder : new RouplexTcpBinder();
        this.rouplexTcpSelector = rouplexTcpBinder.nextRouplexTcpSelector();
        this.selectableChannel = builder.selectableChannel;
        this.attachment = builder.attachment;
    }

    RouplexTcpEndPoint(SelectableChannel selectableChannel, RouplexTcpSelector rouplexTcpSelector) {
        builder = null;
        this.sharedRouplexBinder = true;
        this.rouplexTcpBinder = rouplexTcpSelector.rouplexTcpBinder;
        this.rouplexTcpSelector = rouplexTcpSelector;

        this.selectableChannel = selectableChannel;
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

            return builder != null ? builder.localAddress : null;
        }
    }

    protected void waitForOpen(long expirationTimestamp) throws IOException {
        synchronized (lock) {
            while (!open) {
                if (ioException != null) {
                    throw ioException;
                }

                long waitMillis = expirationTimestamp - System.currentTimeMillis();
                if (waitMillis <= 0) {
                    handleClose(new IOException("Timeout"));
                    // statement not reachable -- exception will be thrown
                }

                try {
                    lock.wait(waitMillis);
                } catch (InterruptedException ie) {
                    throw new IOException("Interrupted");
                }
            }
        }
    }

    protected void updateOpen(@Nullable Exception optionalException) {
        synchronized (lock) {
            if (!(open = optionalException == null)) {
                ioException = optionalException instanceof IOException
                        ? (IOException) optionalException : new IOException(optionalException);
            }

            lock.notifyAll();
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
        handleClose(null);
    }

    protected void handleClose(@Nullable Exception optionalReason) throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                return;
            }

            closed = true;

            if (ioException == null && optionalReason != null) {
                ioException = optionalReason instanceof IOException
                        ? (IOException) optionalReason : new IOException(optionalReason);
            }

            try {
                selectableChannel.close();
            } catch (IOException ioe) {
                ioException = ioe;
            }

            if (rouplexTcpSelector != null) {
                try {
                    rouplexTcpSelector.asyncUnregisterTcpEndPoint(this, optionalReason);
                } catch (IOException ioe) {
                    if (ioException == null) {
                        ioException = ioe;
                    }
                }

                if (!sharedRouplexBinder) {
                    rouplexTcpBinder.close();
                }
            }

            if (ioException != null) {
                throw ioException;
            }
        }
    }

    void closeSilently(Exception reason) {
        try {
            handleClose(reason);
        } catch (IOException ioe) {
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public Object getAttachment() {
        return attachment;
    }

    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }
}
