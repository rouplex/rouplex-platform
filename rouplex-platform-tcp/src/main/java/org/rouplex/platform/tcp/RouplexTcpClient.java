package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.NotThreadSafe;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSocketChannel;
import org.rouplex.platform.io.Receiver;
import org.rouplex.platform.io.Sender;
import org.rouplex.platform.io.Throttle;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * A class representing a TCP client connected to a remote endpoint and optionally to a local one. It provides access
 * to the related input and output streams via {@link Receiver} and {@link Sender} interfaces.
 *
 * Instances of this class are obtained via the builder obtainable in its turn via a call to
 * {@link RouplexTcpBroker#newRouplexTcpClientBuilder()}.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpClient extends RouplexTcpEndPoint {
    protected final static byte[] EOS_BA = new byte[0];
    private final static ByteBuffer EOS_BB = ByteBuffer.allocate(0);

    /**
     * A RouplexTcpClient builder. The builder can only build one client, and once done, any future calls to alter the
     * builder or try to rebuild will fail with {@link IllegalStateException}. The builder is not thread safe.
     */
    @NotThreadSafe
    public static class Builder extends RouplexTcpEndPoint.Builder<RouplexTcpClient, Builder> {
        protected SocketAddress remoteAddress;
        protected String remoteHost;
        protected int remotePort;
        protected RouplexTcpClientListener rouplexTcpClientListener;

        Builder(RouplexTcpBroker rouplexTcpBroker) {
            super(rouplexTcpBroker);
        }

        protected void checkCanBuild() {
            if (remoteAddress == null) {
                throw new IllegalStateException("Missing value for remoteAddress");
            }
        }

        /**
         * An optional {@link SocketChannel}. May be connected, in which case the localAddress, remoteAddress and the
         * eventual {@link SSLContext} are ignored.
         *
         * @param socketChannel
         *          a socket channel, in connected or just in open (and not connected) state
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withSocketChannel(SocketChannel socketChannel) {
            checkNotBuilt();

            this.selectableChannel = socketChannel;
            return builder;
        }

        /**
         * A remote {@link SocketAddress} where to connect to.
         *
         * @param remoteAddress
         *          the remote address to connect to
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withRemoteAddress(SocketAddress remoteAddress) {
            checkNotBuilt();

            this.remoteAddress = remoteAddress;
            return builder;
        }

        /**
         * A remote host name and port, representing a remote address to connect to.
         *
         * @param hostname
         *          the name of the host to connect to
         * @param port
         *          the remote port to connect to
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withRemoteAddress(String hostname, int port) {
            checkNotBuilt();

            this.remoteHost = hostname;
            this.remotePort = port;
            this.remoteAddress = new InetSocketAddress(hostname, port);

            return builder;
        }

        /**
         * Weather the client should connect in secure mode or not. If secure, the sslContext provides the means to
         * access the key and trust stores; if sslContext is null then a relaxed SSLContext, providing no client
         * identity and accepting any server identity will be used. If not secure, the {@link SSLContext} should be
         * null and will be ignored.
         *
         * @param secure
         *          true if the client should connect securely to the remote endpoint
         * @param sslContext
         *          the sslContext to use, or null if an allow-all is preferred
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) {
            checkNotBuilt();

            this.sslContext = secure ? sslContext != null ? sslContext : buildRelaxedSSLContext() : null;
            return builder;
        }

        /**
         * Set the client lifecycle event listener.
         *
         * @param rouplexTcpClientListener
         *          the event listener
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withRouplexTcpClientListener(RouplexTcpClientListener rouplexTcpClientListener) {
            checkNotBuilt();

            this.rouplexTcpClientListener = rouplexTcpClientListener;
            return builder;
        }

        /**
         * Build the client, start its connection and return immediately.
         *
         * @return
         *          the built but not necessarily connected client
         * @throws IOException
         *          if any problems arise during the client creation and connection initialization
         */
        @Override
        public RouplexTcpClient buildAsync() throws IOException {
            checkNotBuilt();
            checkCanBuild();

            if (selectableChannel == null) {
                selectableChannel = sslContext == null ? SocketChannel.open()
                    : SSLSocketChannel.open(sslContext, remoteHost, remotePort, true, null, null);
            }

            return new RouplexTcpClient(this);
        }
    }

    /**
     * Internal class for throttling the incoming traffic.
     */
    class ThrottledReceiver extends Throttle {
        @Nullable
        private Receiver<byte[]> receiver;
        boolean eosReceived;

        private long rateLimitCurrentTimestamp;
        private long rateLimitCurrentBytes;
        private long rateLimitBytes;
        private long rateLimitMillis;

        @Override
        public void setMaxRate(long maxRate, long duration, TimeUnit timeUnit) {
            rateLimitBytes = maxRate;
            rateLimitMillis = timeUnit.toMillis(duration);
            resume(); // so the new values get computed
        }

        @Override
        public void pause() {
            rouplexTcpSelector.asyncPauseInterestOps(selectionKey, SelectionKey.OP_READ, 0 /*forever*/);
        }

        @Override
        public void resume() {
            rouplexTcpSelector.asyncResumeInterestOps(selectionKey, SelectionKey.OP_READ);
        }

        /**
         * Handle the payload which was read from the network by first forwarding it to the receiver (if existent),
         * then calculating the input rate and possibly pausing the producer via the throttle mechanism. If there is
         * no receiver set up, then the payload is simply discarded.
         *
         * @param payload
         *          the payload read from the channels input stream
         * @return
         *          -2 to close the client, -1 to keep reading, 0 to stop reading, or number of millis to pause reading
         */
        long handleSocketInput(byte[] payload) {
            long resumeTimestamp = receiver == null || receiver.receive(payload) ? -1 : 0;

            if (payload == null) {
                return 0; // not used
            }

            if (rateLimitCurrentTimestamp != 0) {
                if (System.currentTimeMillis() > rateLimitCurrentTimestamp) {
                    rateLimitCurrentTimestamp = System.currentTimeMillis() + rateLimitMillis;
                    rateLimitBytes = 0;
                } else {
                    rateLimitCurrentBytes += payload.length;
                    if (rateLimitCurrentBytes > rateLimitBytes) {
                        resumeTimestamp = rateLimitCurrentTimestamp;
                    }
                }
            }

            return (eosReceived = payload == EOS_BA) ? throttledSender.eosApplied ? -2 : 0 : resumeTimestamp;
        }
    }

    /**
     * Internal class for throttling the outgoing traffic.
     */
    class ThrottledSender implements Sender<ByteBuffer> {
        private final LinkedList<ByteBuffer> writeBuffers = new LinkedList<ByteBuffer>();

        private long remaining;
        private Throttle throttle;
        boolean paused;
        boolean eosReceived;
        boolean eosApplied;

        /**
         * Copy bytes from source to destination, adjusting their positions accordingly. This call does not fail if
         * destination cannot accommodate all the bytes available in source, but copies as many as possible.
         *
         * @param source
         *          source buffer
         * @param destination
         *          destination buffer
         * @return
         *          the number of bytes copied
         */
        private int transfer(ByteBuffer source, ByteBuffer destination) {
            int srcRemaining;
            int destRemaining;

            if ((srcRemaining = source.remaining()) > (destRemaining = destination.remaining())) {
                int limit = source.limit();
                source.limit(source.position() + destRemaining);
                destination.put(source);
                source.limit(limit);
                return destRemaining;
            } else {
                if (source.hasRemaining()) {
                    destination.put(source);
                }

                return srcRemaining;
            }
        }

        /**
         * Send a payload to the remote endpoint.
         *
         * The units of data to be sent via the Sender is a {@link ByteBuffer}. Upon the return from this call the
         * ByteBuffer's position will have advanced to take into account the number of bytes sent.
         *
         * In particular,
         *
         * (1) sending a ByteBuffer of 0 remaining bytes is interpreted as end-of-stream and will be handled by shutting
         * down the underlying socketChannel's output stream (after the underlying buffers are completely flushed out).
         * Consequently, the remote endpoint will receive an empty byte array, indicating the end-of-stream. If the remote
         * endpoint has closed its output stream then the remote endpoint will be closed, then fire the appropriate
         * disconnect event (with no exception, meaning graceful).
         *
         * (2) sending a null ByteBuffer indicates an abrupt close of the stream and will close this client and its
         * underlying socket channel without trying to flush any remaining data first. The remote endpoint will produce a
         * {@link Receiver#receive(Object)} with a null payload, and will close in its turn, firing the appropriate
         * disconnect event with the related exception.
         *
         * @param payload
         *          the payload to be sent
         * @throws IOException
         *          if the Sender or the RouplexTcpClient is already closed, or any other issue sending the payload
         */
        @Override
        public void send(ByteBuffer payload) throws IOException {
            synchronized (lock) {
                if (eosReceived) {
                    throw new IOException("Sender is closed");
                }

                if (isClosed()) {
                    throw new IOException("TcpClient is closed");
                }

                if (payload == null) { // null is marker for client (abrupt) close
                    close();
                    return;
                }

                if (payload.hasRemaining()) {
                    if (paused) {
                        return;
                    }

                    paused = remaining < payload.remaining();
                    int writeSize = (int) (paused ? remaining : payload.remaining());
                    if (writeSize == 0) {
                        return;
                    }

                    remaining -= writeSize;
                    ByteBuffer writeBuffer = ByteBuffer.allocate(writeSize);
                    transfer(payload, writeBuffer);
                    writeBuffer.flip();
                    writeBuffers.add(writeBuffer);
                } else {
                    eosReceived = true;
                    writeBuffers.add(EOS_BB);
                }

                if (selectionKey != null) { // call to send comes before the selector has registered our channel
                    // no risk of deadlocking (since we'd be acquiring selector's lock as well) since selector always
                    // fires lock free
                    rouplexTcpSelector.asyncResumeInterestOps(selectionKey, SelectionKey.OP_WRITE);
                }

                /**
                 * todo: Consider reworking the following block by calling pause() from selector's context
                 *
                 * As it is, the the pause / resume order is guaranteed, but there is a chance for a potential deadlock
                 * if the user uses two threads T1 and T2, and a second lock lock2, and accesses the send() method
                 * without a proper synchronization scheme (i.e. T1 and T2 don't synchronize access to send() by using
                 * the same lock object). However unlikely, the following sequence would result in a deadlock:
                 * 0. T1 -> Has *not* acquired lock2 (premise)
                 * 1. T1 -> Enters this.send()
                 * 2. T2 -> Acquires lock2
                 * 3. T1 -> Acquires lock
                 * 4. T1 -> Enters throttle.pause() -> Tries to acquire lock2 (per user's possible implementation)
                 * 5. T2 -> Enters this.send() -> Tries to acquire lock
                 * 6. Deadlock!!
                 *
                 * If pulled out of the synchronized block (as-is), the pause / resume order would not be guaranteed,
                 * since after relinquishing the lock and before calling pause(), the selector's thread might free some
                 * write space, then realizing that the sender is in paused mode, fire the resume() call before the
                 * pause() gets called. The solution would be to have selector's thread call pause() from its context.
                 */
                if (paused) {
                    try {
                        throttle.pause();
                    } catch (RuntimeException re) {
                        close(re);
                    }
                }
            }
        }

        ByteBuffer pollFirstWriteBuffer() {
            synchronized (lock) {
                return writeBuffers.isEmpty() ? null : writeBuffers.getFirst();
            }
        }

        /**
         * Called by {@link RouplexTcpSelector} after all the content of a {@link ByteBuffer} has been written to the
         * network. Eventually this call will notify the sender to resume writes. Always called from the same thread,
         * responsible for work in the underlying channel.
         *
         * @param writeBuffer
         *          the writeBuffer in the internal queue, and to be removed
         * @return
         *          -2 to close the client, 0 to keep going
         */
        int removeWriteBuffer(ByteBuffer writeBuffer) {
            Throttle throttle;

            synchronized (lock) {
                writeBuffers.removeFirst();
                remaining += writeBuffer.limit();

                if (paused) {
                    paused = false;
                    throttle = this.throttle;
                } else {
                    throttle = null;
                }
            }

            if (throttle != null) {
                throttle.resume();
            }

            return (eosApplied = writeBuffer == EOS_BB) && throttledReceiver.eosReceived ? -2 : 0;
        }
    }

    /**
     * Called by {@link RouplexTcpSelector} when the underlying channel just got connected to the remote endpoint.
     */
    void handleConnected() {
        handleOpen();

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onConnected(this);
        }
    }

    /**
     * Called by {@link RouplexTcpSelector} when the underlying channel just failed connection to the remote endpoint.
     */
    void handleConnectionFailed(@Nullable Exception optionalReason) {
        setExceptionAndCloseChannel(optionalReason);

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onConnectionFailed(this, ioException);
        }
    }

    /**
     * Called by {@link RouplexTcpSelector} when the underlying channel just got disconnected from the remote endpoint.
     * Always called from the same thread, which is also the one updating eosReceived and eosApplied, so no need for
     * synchronization here.
     */
    boolean handleDisconnected(@Nullable Exception optionalReason) {
        setExceptionAndCloseChannel(optionalReason);
        boolean drainedChannels = throttledReceiver.eosReceived && throttledSender.eosApplied;

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onDisconnected(this, ioException, drainedChannels);
        }

        return drainedChannels;
    }

    protected final RouplexTcpServer rouplexTcpServer; // not null if this channel was created by a rouplexTcpServer
    protected final RouplexTcpClientListener rouplexTcpClientListener;

    protected final ThrottledSender throttledSender = new ThrottledSender();
    protected final ThrottledReceiver throttledReceiver = new ThrottledReceiver();

    private SelectionKey selectionKey;
    /**
     * Construct an instance using a prepared {@link Builder} instance.
     *
     * @param builder
     *          the builder providing all needed info for creation of the client
     */
    RouplexTcpClient(Builder builder) throws IOException {
        super(builder);

        rouplexTcpServer = null;
        rouplexTcpClientListener = builder.rouplexTcpClientListener;

        SocketChannel socketChannel = (SocketChannel) selectableChannel;
        if (builder.sendBufferSize != 0) {
            socketChannel.socket().setSendBufferSize(builder.sendBufferSize);
        }
        if (builder.receiveBufferSize != 0) {
            socketChannel.socket().setReceiveBufferSize(builder.receiveBufferSize);
        }

        if (!socketChannel.isConnectionPending() && !socketChannel.isConnected()) {
            socketChannel.configureBlocking(false);
            socketChannel.connect(builder.remoteAddress);
        }

        rouplexTcpSelector.asyncRegisterTcpEndPoint(this);
    }

    /**
     * Construct an instance by wrapping a {@link SocketChannel} obtained via a {@link ServerSocketChannel#accept()}.
     *
     * @param socketChannel
     *          the underlying channel to use for reading and writing to the network
     * @param rouplexTcpSelector
     *          the rouplexTcpSelector wrapping the {@link Selector} used to register the channel
     * @param rouplexTcpServer
     *          the rouplexTcpServer which accepted the underlying channel
     */
    RouplexTcpClient(SocketChannel socketChannel, RouplexTcpSelector rouplexTcpSelector, RouplexTcpServer rouplexTcpServer) throws IOException {
        super(socketChannel, rouplexTcpSelector);

        this.rouplexTcpServer = rouplexTcpServer;
        this.rouplexTcpClientListener = rouplexTcpServer.rouplexTcpClientListener;

        if (rouplexTcpServer.sendBufferSize != 0) {
            socketChannel.socket().setSendBufferSize(rouplexTcpServer.sendBufferSize);
        }
        if (rouplexTcpServer.receiveBufferSize != 0) {
            socketChannel.socket().setReceiveBufferSize(rouplexTcpServer.receiveBufferSize);
        }
    }

    /**
     * Get the remote endpoint address where this instance is connected, or is connecting to.
     *
     * @return
     *          the remote endpoint address
     * @throws IOException
     *          if the instance is already closed or any other problem retrieving the remote address
     */
    public SocketAddress getRemoteAddress() throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IOException("Already closed");
            }

            // jdk1.7+ return ((SocketChannel) selectableChannel).getRemoteAddress();
            return ((SocketChannel) selectableChannel).socket().getRemoteSocketAddress();
        }
    }

    /**
     * This method is called from {@link RouplexTcpSelector} when it registers this endpoint.
     *
     * @param selectionKey
     *          the selection key, result of registering this channel with the RouplexTcpSelector's selector
     */
    void setSelectionKey(SelectionKey selectionKey) {
        synchronized (lock) {
            this.selectionKey = selectionKey;
        }
    }

    /**
     * Obtain (or get) the {@link Sender} to use for sending bytes via this client. The throttle parameter will be
     * used by this instance to notify when to resume after a pause.
     *
     * The units of data to be sent via the Sender is a {@link ByteBuffer}. Upon the return from this call the
     * ByteBuffer's position will have advanced to take into account the number of bytes sent.
     *
     * In particular,
     *
     * (1) Sending a ByteBuffer of 0 remaining bytes is interpreted as end-of-stream and will be handled by shutting
     * down the underlying socketChannel's output stream (after the underlying buffers are completely flushed out).
     * Consequently, the remote endpoint will receive an empty byte array, indicating the end-of-stream. If the remote
     * endpoint has closed its output stream then the remote endpoint will be closed, then fire the appropriate
     * disconnect event (with no exception, meaning graceful).
     *
     * (2) Sending a null ByteBuffer indicates an abrupt close of the stream and will close this client and its
     * underlying socket channel without trying to flush any remaining data first. The remote endpoint will produce a
     * {@link Receiver#receive(Object)} with a null payload, and will close in its turn, firing the appropriate
     * disconnect event with the related exception.
     *
     * @param throttle
     *          a throttle instance to be used to control the traffic flow (via calls to pause / resume)
     * @param sendBufferSize
     *          the size of the tcp client buffer. If 0 then the underlying socket's sendBufferSize will be used, or if
     *          that cannot be retrieved, a default value of 64kb will be used.
     * @return
     *          the sender to be used to send ByteBuffer payloads.
     */
    public Sender<ByteBuffer> obtainSendChannel(Throttle throttle, int sendBufferSize) {
        synchronized (lock) {
            if (throttledSender.throttle != null) {
                throw new IllegalStateException("Send channel already hooked.");
            }

            if (throttle == null) {
                throw new IllegalArgumentException("Throttle cannot be null.");
            }

            if (sendBufferSize == 0) {
                try {
                    sendBufferSize = ((SocketChannel) selectableChannel).socket().getSendBufferSize();
                    // not likely, but let's protect against the case where it still returns 0
                } catch (IOException ioe) {
                    // it will remain 0
                }
            }

            throttledSender.remaining = sendBufferSize != 0 ? sendBufferSize : 0x10000; // 64kb default
            throttledSender.throttle = throttle;
            return throttledSender;
        }
    }

    /**
     * Assign (or set) the {@link Receiver} which will be receiving the bytes read by this client. Whenever there are
     * bytes available, they will be passed to the receiver by this client via a {@link Receiver#receive(Object)} call.
     *
     * See {@link Receiver} for general details about handling the payload received. In this particular case, the
     * payload must be handled promptly (usually asynchronously by copying the reference of the byte buffer and
     * assigning a worker thread to handle it).
     *
     * The receiver will be called with byte[] as parameter. In particular,
     *
     * (1) A {@link Receiver#receive(Object)} call with a byte array of size 0 (zero) must be interpreted as
     * end-of-stream sent from the remote endpoint. If the client has already sent its end-of-stream via the
     * {@link Sender}, the client will be subsequently shut down and the appropriate disconnect event with null as
     * exception (meaning graceful) will be fired.
     *
     * (2) A call with a null byte array must be interpreted as a disrupted stream, either from the remote end
     * explicitly, or due to communication problems. In the latest case this client will also close itself and fire the
     * appropriate disconnect event with the related exception.
     *
     * This API may look a bit less descriptive then, say, having a proper close() event, but having one method call
     * only, provides for serializability of the events leaving no room for interpretations as to which event happened
     * first.
     *
     * @param receiver
     *          the receiver instance for receiving the payloads
     * @param started
     *          true if the caller is prepared to receive payloads right away, false to postpone the firing of incoming
     *          payloads until a {@link Throttle#resume()} call.
     * @return
     *          the throttle construct which the receiver can use to control the flow of receiving bytes
     */
    public Throttle assignReceiveChannel(@Nullable Receiver<byte[]> receiver, boolean started) {
        synchronized (lock) {
            if (throttledReceiver.receiver != null) {
                throw new IllegalStateException("Receive channel already hooked.");
            }

            throttledReceiver.receiver = receiver;
            if (started) {
                throttledReceiver.resume();
            }

            return throttledReceiver;
        }
    }

    /**
     * Get the local server this client belongs to, or null if this client in not related to a local server.
     *
     * @return
     *          the RouplexTcpServer this client belongs to, if any
     */
    public RouplexTcpServer getRouplexTcpServer() {
        return rouplexTcpServer;
    }

    private static final TrustManager trustAll = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /**
     * Convenience method for building client side {@link SSLContext} instances that do not provide a client identity
     * and accept any server identity.
     *
     * @return
     *          a relaxed sslContext
     */
    public static SSLContext buildRelaxedSSLContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {trustAll}, null);
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
