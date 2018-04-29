package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
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
 * A base class for {@link TcpClient} and {@link TcpServer} containing functionality inherent to both.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
abstract class TcpEndPoint implements Closeable {
    /**
     * The base builder, to be inherited by the respective builders. A builder instance can only be used to build only
     * once.
     *
     * @param <T> The type of the artifact to be built, such as {@link TcpClient} or {@link TcpServer}.
     * @param <B> The type of the Builder itself, such as {@link TcpClient.Builder} or {@link TcpServer.Builder}.
     */
    @NotThreadSafe
    static abstract class Builder<T extends TcpEndPoint, B extends Builder> {
        protected final TcpSelector tcpSelector;
        protected SocketAddress localAddress;
        protected Object attachment;

        protected SSLContext sslContext;
        protected SelectableChannel selectableChannel;

        // reference to "this" to get around generics invariance.
        // used also as a marker for an already built endpoint (when set to null)
        protected B builder;

        Builder(TcpBroker tcpBroker) {
            tcpSelector = tcpBroker.nextTcpSelector();
            builder = (B) this;
        }

        protected void checkNotBuilt() {
            if (builder == null) {
                throw new IllegalStateException("Already built. Create a new builder to build a new instance.");
            }
        }

        synchronized public B withLocalAddress(SocketAddress localAddress) {
            checkNotBuilt();

            this.localAddress = localAddress;
            return builder;
        }

        synchronized public B withLocalAddress(@Nullable String hostname, int port) {
            checkNotBuilt();

            this.localAddress = new InetSocketAddress(hostname == null ? "localhost" : hostname, port);
            return builder;
        }

        synchronized public B withAttachment(@Nullable Object attachment) {
            checkNotBuilt();

            this.attachment = attachment;
            return builder;
        }

        public abstract T build() throws IOException;
    }

    protected final Object lock = new Object();
    protected final TcpSelector tcpSelector;
    protected final SelectableChannel selectableChannel;

    @GuardedBy("lock") // not final, set and changed by user
    protected Object attachment;

    @GuardedBy("lock") // only set synchronously from broker
    protected boolean open;

    @GuardedBy("lock")
    protected boolean closed;

    @GuardedBy("lock")
    protected IOException ioException;

    /**
     * Constructor to create an endpoint either via the builder or by wrapping a socket channel, such as the one
     * obtained by a {@link ServerSocketChannel#accept()}.
     *
     * @param selectableChannel the already connected {@link SocketChannel}
     * @param tcpSelector       the {@link TcpSelector} to be used with this socket channel
     * @param attachment        the attachment object to be used with this instance, can be anything the user wants
     */
    TcpEndPoint(SelectableChannel selectableChannel, TcpSelector tcpSelector, Object attachment) {
        this.selectableChannel = selectableChannel;
        this.tcpSelector = tcpSelector;
        this.attachment = attachment;
    }

    SelectableChannel getSelectableChannel() {
        return selectableChannel;
    }

    /**
     * Get the local address of the endpoint by returning the local address of the channel.
     *
     * @return the resolved local address where the endpoint is bound to
     * @throws IOException if the endpoint is closed or could not get the channel's local address
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
                "Internal implementation error: Class %s is not a NetworkChannel", selectableChannel.getClass()));
        }
    }

    /**
     * Wait for this channel to connect to its remote endpoint.
     *
     * @param expirationTimestamp the expiration time in epoch time. If the channel is not opened by this time, the call will throw a
     *                            "Timeout" IOException instead.
     * @throws IOException if the channel is not opened by expirationTimestamp time, it has been already closed, or gets closed
     *                     asynchronously, or any other problem trying to connect.
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
                        closeAndUnregister(new IOException("Interrupted", ie));
                    }
                } else {
                    closeAndUnregister(new IOException("Timeout"));
                }
            }
        }
    }

    /**
     * Update the internal state to open, and notify any waiting threads.
     */
    protected void handleOpen() {
        synchronized (lock) {
            if (ioException == null) {
                open = true;
                lock.notifyAll();
            }
        }
    }

    @Override
    public void close() throws IOException {
        closeAndUnregister(null);
    }

    private void closeAndUnregister(Exception e) throws IOException {
        setExceptionAndCloseChannel(e);
        tcpSelector.asyncUnregisterTcpEndPoint(this, null);
    }

    /**
     * Set the state to closed, and close the selectableChannel. The optionalException, or the exception during
     * the selectableChannel close, if any, becomes the instance's ioException
     *
     * @param optionalException null if the endpoint closed without any problems. Otherwise, its value will be noted and will be thrown
     *                          by the eventual thread calling {@link #waitForOpen(long)}.
     */
    void setExceptionAndCloseChannel(@Nullable Exception optionalException) {
        synchronized (lock) {
            if (!closed) {
                closed = true;

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
        synchronized (lock) {
            return attachment;
        }
    }

    public void setAttachment(Object attachment) {
        synchronized (lock) {
            this.attachment = attachment;
        }
    }

    abstract void handleRegistration() throws Exception;
    abstract void handleUnregistration(@Nullable Exception optionalReason);
}
