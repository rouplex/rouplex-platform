package org.rouplex.platform.tcp;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.commons.builders.SingleInstanceBuilder;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A base class for {@link TcpClient} and {@link TcpServer} containing functionality inherent to both.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
abstract class TcpEndPoint implements Closeable {
    public enum AutoCloseCondition {
        ON_CHANNEL_EOS(0b1),
        ON_CHANNEL_EXCEPTION(0b10),
        ON_USER_CALLBACK_EXCEPTION(0b100),
        ON_ANY_CONDITION(0b111);

        protected final int mask;

        AutoCloseCondition(int mask) {
            this.mask = mask;
        }
    }

    /**
     * The base builder for Tcp related instances, to be inherited by the respective builders. A builder instance can
     * only be used to build only once.
     *
     * Not thread safe.
     *
     * @param <T> The type of the instance to be built off this builder, such as {@link TcpClient} or {@link TcpServer}.
     * @param <B> The type of the Builder itself, such as {@link TcpClient.Builder} or {@link TcpServer.Builder}.
     */
    protected abstract static class TcpEndPointBuilder<T, B extends TcpEndPointBuilder> extends SingleInstanceBuilder<T, B> {
        protected final TcpReactor.TcpSelector tcpSelector;
        protected Executor eventsExecutor;

        protected SocketAddress localAddress;
        protected SSLContext sslContext;
        protected SelectableChannel selectableChannel;

        protected int readBufferSize = 0x1000;
        protected int writeBufferSize = 0x1000;
        protected boolean onlyAsync;
        protected boolean useDirectBuffers;
        protected int autoCloseMask = 0b111;

        protected TcpEndPointBuilder(TcpReactor tcpReactor) {
            tcpSelector = tcpReactor.nextTcpSelector();
            eventsExecutor = tcpSelector.getReactor().eventsExecutor;
        }

        /**
         * A local {@link SocketAddress} where to bind to.
         *
         * @param localAddress
         *          The local address to bind to, or 0 to bind at any available port
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withLocalAddress(SocketAddress localAddress) {
            checkNotBuilt();
            checkLocalAddressSettable();

            this.localAddress = localAddress;
            return builder;
        }

        /**
         * A local hostname and port where to bind to.
         *
         * @param hostname
         *          The local hostname to bind to, or null to bind to the localhost (later maybe bind in all of them)
         * @param port
         *          The port to bind to, or 0 to bind to any available port
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withLocalAddress(@Nullable String hostname, int port) {
            checkNotBuilt();
            checkLocalAddressSettable();

            this.localAddress = new InetSocketAddress(hostname == null ? "localhost" : hostname, port);
            return builder;
        }

        /**
         * Enable SSL/TLS and use sslContext for all related configurations, such as access to the key and trust
         * stores, ciphers and protocols to use. If sslContext is null then a plain connection will be used.
         *
         * If a socketChannel is already set, then the connection's security is governed by its settings, and a
         * call to this method will fail with {@link IllegalStateException}.
         *
         * @param sslContext
         *          The sslContext to use for SSL/TLS or null if plain connection is preferred.
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withSecure(@Nullable SSLContext sslContext) {
            checkNotBuilt();

            if (selectableChannel != null) {
                throw new IllegalStateException(
                    "SocketChannel is already set and cannot coexist with Secure/SSLContext");
            }

            this.sslContext = sslContext;
            return builder;
        }

        /**
         * Use this initial buffer size for the read channel. This setting can be changed on the built 
         * {@link TcpReadChannel} later on. This setting has no effect if the eventsExecutor is set to null.
         *
         * Default is 4Kb.
         *
         * @param readBufferSize
         *          The size for the buffer holding data read from tcp stack but not from user, in bytes.
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withReadBufferSize(int readBufferSize) {
            checkNotBuilt();

            this.readBufferSize = readBufferSize;
            return builder;
        }

        /**
         * Use this initial buffer size for the write channel. This setting can be changed on the built 
         * {@link TcpWriteChannel} later on. This setting has no effect if the eventsExecutor is set to null.
         *
         * Default is 4Kb.
         *
         * @param writeBufferSize
         *          The size for the buffer holding data writen from user but not yet to tcp stack, in bytes.
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withWriteBufferSize(int writeBufferSize) {
            checkNotBuilt();

            this.writeBufferSize = writeBufferSize;
            return builder;
        }

        /**
         * If set to true:
         *
         * In a {@link TcpWriteChannel} created subsequently via this builder, all the calls to write to the
         * channel will result in the content being written to the internal buffer, and the call returning
         * immediately.
         *
         * In a {@link TcpReadChannel} created subsequently via this builder, all the calls to read from the
         * channel will result in the content being read from the internal buffer, and the call returning
         * immediately.
         *
         * In that case, the calling thread may be waiting the least in the case of an ssl channel, since the
         * heavier task of performing the crypto work is done in the context of another thread.
         *
         * If set to false:
         *
         * In a {@link TcpWriteChannel} created subsequently via this builder, there will be an attempt to send as
         * many bytes possible (honoring an eventual timeout set), and the rest will be kept in the internal buffer,
         * up to the point it has capacity (the rest will be left in the caller's {@link ByteBuffer} argument).
         *
         * In a {@link TcpReadChannel} created subsequently via this builder, there will be an attempt to read as
         * many bytes possible (honoring an eventual timeout set), and any content not being able to fit in
         * caller's {@link ByteBuffer} argument will be left in the inner buffer.
         *
         * This setting can prove useful in case of secure channels where all the crypto call must be performed
         * from the specific thread pool that the client is using.
         *
         * Default is false.
         *
         * @param onlyAsync
         *          True for always writing the bytes asynchronously, false otherwise
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withOnlyAsync(boolean onlyAsync) {
            checkNotBuilt();

            this.onlyAsync = onlyAsync;
            return builder;
        }

        /**
         * If true the class will internally create {@link java.nio.DirectByteBuffer}, otherwise it will create a
         * {@link ByteBuffer}.
         *
         * Default is false.
         *
         * @param useDirectBuffers
         *          True for internally creating a {@link java.nio.DirectByteBuffer} instance, false for a
         *          {@link ByteBuffer} instance
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withUseDirectBuffers(boolean useDirectBuffers) {
            checkNotBuilt();

            this.useDirectBuffers = useDirectBuffers;
            return builder;
        }

        /**
         * An optional {@link Set<AutoCloseCondition>} to define the closing strategy for the {@link TcpClient} that
         * will be built via this builder or the TcpClients that will be created from a {@link TcpServer} which in turn
         * is created via this builder.
         *
         * @param autoCloseConditions
         *          A set of disjointed {@link AutoCloseCondition} values
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withAutoCloseConditions(Set<AutoCloseCondition> autoCloseConditions) {
            checkNotBuilt();

            autoCloseMask = 0;
            if (autoCloseConditions != null) {
                for (AutoCloseCondition condition : autoCloseConditions) {
                    autoCloseMask |= condition.mask;
                }
            }

            return builder;
        }

        /**
         * An optional {@link Executor} that will be used to call the listeners and callbacks on {@link TcpClient} and
         * {@link TcpServer} instances served by this reactor.
         *
         * If set to null then the calls will be performed directly from the reactor's threads. This is an advanced
         * setting and achieves better performance but the callee must only be performing non blocking calls not to
         * delay the control to the caller which will be then tending to other clients ready for IO.
         *
         * If set to a non-null value, then the executor should provide enough threads to tend to as many clients
         * simultaneously, without having them interfere with others by hijacking the calling thread.
         *
         * If left unset, then the executor provided via the {@link TcpReactor} will be used. Please notice that this
         * executor is different from the thread pool used inside the reactor for selection.
         *
         * The {@link #close()} call will not attempt to close this executor.
         *
         * @param eventsExecutor
         *          The executor to be used, or null for all calls to performed directly from the reactor's threads
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withEventsExecutor(Executor eventsExecutor) {
            checkNotBuilt();

            this.eventsExecutor = eventsExecutor;
            return builder;
        }

        protected abstract void checkLocalAddressSettable();
    }

    protected final Object lock = new Object();
    protected final TcpReactor.TcpSelector tcpSelector;
    protected final SelectableChannel selectableChannel;
    protected final Executor eventsExecutor;
    protected final int autoCloseMask;

    // not final, set and changed by user
    @GuardedBy("lock") protected Object attachment;

    // only set from the tcpSelector thread, but can be queried by other threads, hence the guarding
    @GuardedBy("lock") protected boolean open;

    // can be set from any thread and read from any thread, hence the guarding
    @GuardedBy("lock") protected boolean closed;

    @GuardedBy("lock")
    protected IOException ioException;

    // Not essential
    private String debugId;

    /**
     * Constructor to create an endpoint either via the builder or by wrapping a socket channel, such as the one
     * obtained by a {@link ServerSocketChannel#accept()}.
     *
     * @param selectableChannel
     *          The already connected {@link SocketChannel}
     * @param tcpSelector
     *          The {@link TcpReactor.TcpSelector} to be used with this socket channel
     */
    protected TcpEndPoint(SelectableChannel selectableChannel, TcpReactor.TcpSelector tcpSelector, TcpEndPointBuilder builder) {
        this.selectableChannel = selectableChannel;
        this.tcpSelector = tcpSelector;
        this.eventsExecutor = builder.eventsExecutor;
        this.autoCloseMask = builder.autoCloseMask;
    }

    abstract void syncHandleRegistration();
    abstract void syncHandleUnregistration(@Nullable Exception optionalReason);

    /**
     * Get the local address of the endpoint by returning the local address of the channel.
     *
     * @return
     *          The resolved local address where the endpoint is bound to
     * @throws
     *          IOException if the endpoint is closed or could not get the channel's local address
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
     * @param expirationTimestamp
     *          The expiration time in epoch time. If the channel is not opened by this time, the call will throw a
     *          "Timeout" IOException instead.
     * @throws
     *          IOException if the channel is not opened by expirationTimestamp time, it has been already closed, or
     *          gets closed asynchronously, or any other problem trying to connect.
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
                        continue;
                    } catch (InterruptedException ie) {
                        // fall through and fail
                    }
                }

                closeAndAsyncUnregister(new IOException("Timeout"));
            }
        }
    }

    /**
     * Update the internal state to 'open' and notify any waiting threads.
     */
    protected void syncHandleOpen() {
        synchronized (lock) {
            if (ioException == null) {
                open = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * The main handler for any exception caught.
     *
     * @param condition
     *          The condition in which this exception was caught. If this condition is part of the set of the
     *          {@link TcpClient} auto close conditions, then the client will be cosed.
     * @param exception
     *          The exception which was caught
     * @param synchronously
     *          If this is a call coming from one of the {@link TcpReactor} owned threads
     */
    protected void handleException(AutoCloseCondition condition, Exception exception, boolean synchronously) {
        if ((autoCloseMask & condition.mask) != 0) {
            if (synchronously) {
                setExceptionAndCloseChannel(exception);
                syncHandleUnregistration(exception);
            } else {
                closeAndAsyncUnregister(exception);
            }
        }
    }

    @Override
    public void close() throws IOException {
        closeAndAsyncUnregister(null);
    }

    protected void closeAndAsyncUnregister(Exception exception) {
        setExceptionAndCloseChannel(exception);

        try {
            tcpSelector.asyncUnregisterTcpEndPoint(TcpEndPoint.this, null);
        } catch (IOException ioe) {
            // selector has been closed, nothing can be done here
        }
    }

    /**
     * Set the state to closed, and close the selectableChannel. The optionalException, or the exception during
     * the selectableChannel close, if any, becomes the instance's ioException
     *
     * @param optionalException
     *          Null if the endpoint closed without any problems. Otherwise, its value will be noted and will be thrown
     *          by calls to this client (including {@link #waitForOpen(long)}).
     */
    private void setExceptionAndCloseChannel(@Nullable Exception optionalException) {
        synchronized (lock) {
            if (!closed) {
                closed = true;

                if (ioException == null && optionalException != null) {
                    ioException = optionalException instanceof IOException
                        ? (IOException) optionalException : new IOException(optionalException);
                }

                try {
                    //System.out.println("Closing channel: " + selectableChannel + " " + optionalException);
                    selectableChannel.close();
                } catch (Exception e) {
                    if (ioException == null) {
                        ioException = new IOException("Exception during channel close", e);
                    }
                }

                lock.notifyAll();
            }
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

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
}