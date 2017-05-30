package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.NotThreadSafe;
import org.rouplex.commons.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.*;

/**
 * A base class for {@link RouplexTcpClient} and {@link RouplexTcpServer} containing functionality inherent to both.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RouplexTcpEndPoint implements Closeable {

    /**
     * The base builder, to be inherited by the respective builders. A builder instance can only be used to build once.
     * A builder pattern is preferred here since an endpoint is quite a volatile construct, and all setter and getter
     * access would have needed to be synchronized otherwise. A builder pattern hints no thread safety instead.
     *
     * @param <T>
     *          the type of the artifact to be built, such as {@link RouplexTcpClient}
     * @param <B>
     *          the type of the Builder itself, such as {@link RouplexTcpClient.Builder}
     */
    @NotThreadSafe
    static abstract class Builder<T extends RouplexTcpEndPoint, B extends Builder> {
        protected SocketAddress localAddress;
        protected RouplexTcpBinder rouplexTcpBinder;
        protected int sendBufferSize;
        protected int receiveBufferSize;
        protected Object attachment;

        protected SSLContext sslContext;
        protected SelectableChannel selectableChannel;

        // reference to "this" to get around generics invariance.
        // used also as a marker for an already built endpoint (when set to null)
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
            checkNotBuilt();

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
         *          a non negative value for timeout, in milliseconds. The value 0 is interpreted as blocking
         *          indefinitely until the endpoint is open or fails to open.
         * @return
         *          the built and open RouplexTcpEndPoint
         * @throws IOException
         *          if any exception is thrown during the process of building, or if the timeout has been reached and
         *          the endpoint is not open yet
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

    // not final, set and changed by user
    protected Object attachment;

    protected boolean open;
    private boolean closed;

    private IOException ioException;

    /**
     * Constructor to create an endpoint via a builder. Builder's values will be copied to final instance fields. The
     * builder has created the channel during its {@link Builder#buildAsync()} call.
     *
     * @param builder
     *          the builder to be used for the values
     */
    protected <T extends RouplexTcpEndPoint, B extends Builder> RouplexTcpEndPoint(Builder<T, B> builder) {
        this.builder = builder;
        this.sharedRouplexBinder = builder.rouplexTcpBinder != null;
        this.rouplexTcpBinder = sharedRouplexBinder ? builder.rouplexTcpBinder : new RouplexTcpBinder();
        this.rouplexTcpSelector = rouplexTcpBinder.nextRouplexTcpSelector();
        this.selectableChannel = builder.selectableChannel; // always built by builder
        this.attachment = builder.attachment;
    }

    /**
     * Constructor to create an endpoint by wrapping a socket channel, as in the case of a {@link SocketChannel}
     * obtained by a {@link ServerSocketChannel#accept()}.
     *
     * @param selectableChannel
     *          the already connected {@link SocketChannel}
     * @param rouplexTcpSelector
     *          the {@link RouplexTcpSelector} to be used with this socket channel
     */
    RouplexTcpEndPoint(SelectableChannel selectableChannel, RouplexTcpSelector rouplexTcpSelector) {
        builder = null;
        this.sharedRouplexBinder = true;
        this.rouplexTcpBinder = rouplexTcpSelector.rouplexTcpBinder;
        this.rouplexTcpSelector = rouplexTcpSelector;

        this.selectableChannel = selectableChannel;
    }

    /**
     * Get the local address of the endpoint by returning the local address of the channel.
     *
     * @return
     *          the resolved local address where the endpoint is bound to
     * @throws IOException
     *          if the endpoint is closed or could not get the channel's local address
     */
    public SocketAddress getLocalAddress() throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IOException("Already closed");
            }

            // jdk1.7+ return ((NetworkChannel) selectableChannel).getLocalAddress();
            if (selectableChannel instanceof SocketChannel) {
                return ((SocketChannel) selectableChannel).socket().getLocalSocketAddress();
            }
            if (selectableChannel instanceof ServerSocketChannel) {
                return ((ServerSocketChannel) selectableChannel).socket().getLocalSocketAddress();
            }

            throw new Error(String.format(
                    "Internal implementation error: Class %s is not a NetworkChannel",  selectableChannel.getClass()));
        }
    }

    /**
     * Wait for this channel to connect to its remote endpoint.
     *
     * @param expirationTimestamp
     *          the expiration time in epoch time. If the channel is not opened by this time, the call will throw a
     *          "Timeout" IOException instead.
     * @throws IOException
     *          if the channel is not opened by expirationTimestamp time, it has been already closed or gets closed
     *          asynchronously, or any other problem trying to connect.
     */
    protected void waitForOpen(long expirationTimestamp) throws IOException {
        synchronized (lock) {
            while (!open) {
                if (ioException != null) {
                    throw ioException;
                }

                if (closed) {
                    throw new IOException("Closed");
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

    /**
     * Update the internal state (to "open" if optionalException is null), signaling eventual waiting threads of the
     * new state.
     *
     * @param optionalException
     *          if null, then the endpoint is set to "open" state and ready for communication. Otherwise, its value
     *          will be noted and will be thrown if another thread calls {@link #waitForOpen(long)}.
     */
    protected void handleOpen(@Nullable Exception optionalException) {
        synchronized (lock) {
            if (!(open = optionalException == null)) {
                ioException = optionalException instanceof IOException
                        ? (IOException) optionalException : new IOException(optionalException);
            }

            lock.notifyAll();
        }
    }

    SelectableChannel getSelectableChannel() {
        return selectableChannel;
    }

    @Override
    public void close() throws IOException {
        handleClose(null);
    }

    /**
     * Update internal state to "closed", signaling eventual waiting threads of the new state.
     *
     * @param optionalException
     *          if null, then the endpoint is set to "open" state and ready for communication. Otherwise, its value
     *          will be noted and will be thrown if another thread calls {@link #waitForOpen(long)}.
     *
     * @throws IOException
     *          In case of exception handling the close
     */
    protected void handleClose(@Nullable Exception optionalException) throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                return;
            }

            closed = true;
            lock.notifyAll();

            if (ioException == null && optionalException != null) {
                ioException = optionalException instanceof IOException
                        ? (IOException) optionalException : new IOException(optionalException);
            }

            try {
                selectableChannel.close();
            } catch (IOException ioe) {
                ioException = ioe;
            }

            if (rouplexTcpSelector != null) {
                rouplexTcpSelector.asyncUnregisterTcpEndPoint(this, optionalException);

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
