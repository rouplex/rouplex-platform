package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.NotThreadSafe;
import org.rouplex.commons.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

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
        protected final RouplexTcpSelector rouplexTcpSelector;
        protected SocketAddress localAddress;
        protected int sendBufferSize;
        protected int receiveBufferSize;
        protected Object attachment;

        protected SSLContext sslContext;
        protected SelectableChannel selectableChannel;

        // reference to "this" to get around generics invariance.
        // used also as a marker for an already built endpoint (when set to null)
        protected B builder;

        Builder(RouplexTcpBroker rouplexTcpBroker) {
            rouplexTcpSelector = rouplexTcpBroker.nextRouplexTcpSelector();
            builder = (B) this;
        }

        public abstract T buildAsync() throws IOException;

        protected void checkNotBuilt() {
            if (builder == null) {
                throw new IllegalStateException("Already built. Create a new builder to build a new instance.");
            }
        }

        public B withLocalAddress(SocketAddress localAddress) {
            checkNotBuilt();

            this.localAddress = localAddress;
            return builder;
        }

        public B withLocalAddress(@Nullable String hostname, int port) {
            checkNotBuilt();

            this.localAddress = new InetSocketAddress(hostname == null ? "localhost" : hostname, port);
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

    protected final Object lock = new Object();
    protected final RouplexTcpSelector rouplexTcpSelector;
    protected final SelectableChannel selectableChannel;

    // not final, set and changed by user
    protected Object attachment;

    // only set synchronously from broker
    protected boolean open;

    // only set synchronously from broker
    private boolean closed;

    protected IOException ioException;

    /**
     * Constructor to create an endpoint via a builder. Builder's values will be copied to final instance fields. The
     * builder has created the channel during its {@link Builder#buildAsync()} call.
     *
     * @param builder
     *          the builder to be used for the values
     */
    protected <T extends RouplexTcpEndPoint, B extends Builder> RouplexTcpEndPoint(Builder<T, B> builder) {
        this.rouplexTcpSelector = builder.rouplexTcpSelector;
        this.selectableChannel = builder.selectableChannel; // always provided by builder
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
        this.selectableChannel = selectableChannel;
        this.rouplexTcpSelector = rouplexTcpSelector;
    }

    SelectableChannel getSelectableChannel() {
        return selectableChannel;
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

                long waitMillis = expirationTimestamp - System.currentTimeMillis();
                if (waitMillis > 0) {
                    try {
                        lock.wait(waitMillis);
                    } catch (InterruptedException ie) {
                        setExceptionAndCloseChannel(new IOException("Interrupted", ie));
                    }
                } else {
                    setExceptionAndCloseChannel(new IOException("Timeout"));
                }
            }
        }
    }

    /**
     * Update the internal state to open, and notify any waiting threads.
     */
    void handleOpen() {
        synchronized (lock) {
            if (ioException == null) {
                open = true;
                lock.notifyAll();
            }
        }
    }

    @Override
    public void close() {
        close(null);
    }

    void close(@Nullable Exception optionalException) {
        setExceptionAndCloseChannel(optionalException);
        rouplexTcpSelector.asyncUnregisterTcpEndPoint(this, ioException);
    }

    /**
     * Set the state to closed, and close the selectableChannel. The optionalException, or the exception during
     * the selectableChannel close, if any, becomes the instance's ioException
     *
     * @param optionalException
     *          null if the endpoint closed without any problems. Otherwise, its value will be noted and will be thrown
     *          by the eventual thread calling {@link #waitForOpen(long)}.
     */
    void setExceptionAndCloseChannel(@Nullable Exception optionalException) {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                lock.notifyAll();

                if (ioException == null && optionalException != null) {
                    ioException = optionalException instanceof IOException
                        ? (IOException) optionalException : new IOException(optionalException);
                }

                try {
                    selectableChannel.close();
                } catch (IOException ioe) {
                    if (ioException == null) {
                        ioException = ioe;
                    }
                }
            }
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    String debugId;
    public String getDebugId() {
        return debugId;
    }

    public void setDebugId(String debugId) {
        this.debugId = debugId;
    }

    public Object getAttachment() {
        return attachment;
    }

    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }
}
