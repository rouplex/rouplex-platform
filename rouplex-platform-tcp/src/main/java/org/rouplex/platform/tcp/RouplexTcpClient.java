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
 * to the related output and input streams via {@link Sender} and {@link Receiver} interfaces.
 *
 * Instances of this class are built via the builder obtainable in turn via the {@link RouplexTcpClient#newBuilder()}
 * static method.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpClient extends RouplexTcpEndPoint {
    protected final static byte[] EOS_BA = new byte[0];
    protected final static ByteBuffer EOS_BB = ByteBuffer.allocate(0);

    /**
     * A RouplexTcpClient builder. The builder can only build one client, and once done, any future calls to alter the
     * builder or try to rebuild will fail with {@link IllegalStateException}. The builder is not thread safe.
     */
    @NotThreadSafe
    public static class Builder extends RouplexTcpEndPoint.Builder<RouplexTcpClient, Builder> {
        protected SocketAddress remoteAddress;
        protected RouplexTcpClientListener rouplexTcpClientListener;

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
                selectableChannel = sslContext == null ? SocketChannel.open() : SSLSocketChannel.open(sslContext);
            }

            RouplexTcpClient result = new RouplexTcpClient(this);
            builder = null;
            result.connectAsync();
            return result;
        }
    }

    /**
     * Create a new builder to be used to build a RouplexTcpClient.
     *
     * @return
     *          the new builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Internal class for throttling the incoming traffic.
     */
    class ThrottledReceiver extends Throttle {
        private final SelectionKey selectionKey;
        @Nullable
        private Receiver<byte[]> receiver;
        boolean eosReceived;

        private long rateLimitCurrentTimestamp;
        private long rateLimitCurrentBytes;
        private long rateLimitBytes;
        private long rateLimitMillis;

        private ThrottledReceiver(SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
        }

        @Override
        public boolean setMaxRate(long maxRate, long duration, TimeUnit timeUnit) {
            rateLimitBytes = maxRate;
            rateLimitMillis = timeUnit.toMillis(duration);
            resume(); // so the new values get computed
            return true;
        }

        @Override
        public boolean pause() {
            rouplexTcpSelector.asyncPauseRead(selectionKey, Long.MAX_VALUE); // 295 million years
            return true;
        }

        @Override
        public void resume() {
            rouplexTcpSelector.asyncResumeRead(selectionKey);
        }

        /**
         * Handle the payload which was read from the network by first forwarding it to the receiver (if existent),
         * then calculating the input rate and possibly pausing the producer via the throttle mechanism. If there is
         * no receiver set up, then the payload is simply discarded.
         *
         * @param payload
         *          the payload read from the channels input stream
         * @return
         *          true if there is no receiver or if the receiver was able to receive the payload, false otherwise
         */
        boolean consumeSocketInput(byte[] payload) {
            boolean consumed = receiver == null || receiver.receive(payload);

            if (payload == null) {
                return true;
            }

            if (rateLimitCurrentTimestamp != 0) {
                if (System.currentTimeMillis() > rateLimitCurrentTimestamp) {
                    rateLimitCurrentTimestamp = System.currentTimeMillis() + rateLimitMillis;
                    rateLimitBytes = 0;
                } else {
                    rateLimitCurrentBytes += payload.length;
                    if (rateLimitCurrentBytes > rateLimitBytes) {
                        rouplexTcpSelector.asyncPauseRead(selectionKey, rateLimitCurrentTimestamp);
                    }
                }
            }

            if (eosReceived = payload == EOS_BA) {
                handleEos();
            }

            return consumed;
        }
    }

    /**
     * Internal class for throttling the outgoing traffic.
     */
    class ThrottledSender implements Sender<ByteBuffer> {
        private final LinkedList<ByteBuffer> writeBuffers = new LinkedList<ByteBuffer>();
        private final SelectionKey selectionKey;

        private long remaining;
        private Throttle throttle;
        boolean paused;
        boolean eosReceived;
        boolean eosApplied;

        private ThrottledSender(SelectionKey selectionKey) {
            this.selectionKey = selectionKey;

            try {
                remaining = ((SocketChannel) selectableChannel).socket().getSendBufferSize();
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

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
            int writeSize;

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

                if (!payload.hasRemaining()) { // empty buffer is marker for channel (graceful) close
                    payload = EOS_BB;
                    eosReceived = true;
                } else if (paused) { // don't remove else keyword since we must always accept the EOS for delivery
                    return;
                }

                paused = remaining < payload.remaining();
                writeSize = (int) (paused ? remaining : payload.remaining());
                remaining -= writeSize;
            }

            ByteBuffer writeBuffer;
            if (payload != EOS_BB) {
                // potentially costly operations in this block so we must perform outside the lock space especially
                // since the removeWriteBuffer is performed from the RouplexTcpBinder's single thread responsible to go
                // over all the channels monitored!!!
                writeBuffer = ByteBuffer.allocate(writeSize);
                transfer(payload, writeBuffer);
                writeBuffer.flip();
            } else {
                writeBuffer = EOS_BB;
            }

            Throttle throttle;
            synchronized (lock) {
                writeBuffers.add(writeBuffer);
                throttle = paused ? this.throttle : null;
            }

            rouplexTcpSelector.asyncResumeWrite(selectionKey);

            if (throttle != null) {
                try {
                    // todo, pause / resume order not guaranteed here, options include (in order of preference)
                    // (1) fire it off the binder's thread, (2) remove the pause() since the caller's hasRemaining
                    // would imply the pause, or (3) fire them from within the locked space (at risk of deadlocks)
                    throttle.pause();
                } catch (RuntimeException re) {
                    closeSilently(re);
                }
            }
        }

        ByteBuffer pollFirstWriteBuffer() {
            synchronized (lock) {
                return writeBuffers.isEmpty() ? null : writeBuffers.iterator().next();
            }
        }

        /**
         * Called after all the content of a {@link ByteBuffer} has been written to the network. Eventually resume
         * the writes.
         *
         * @param writeBuffer
         *          the writeBuffer in the internal que, and to be removed
         */
        void removeWriteBuffer(ByteBuffer writeBuffer) {
            Throttle throttle;

            synchronized (lock) {
                writeBuffers.remove(writeBuffer);
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

            if (writeBuffer == EOS_BB) {
                eosApplied = true;
                handleEos();
            }
        }
    }

    /**
     * Update the RouplexTcpClient taking into consideration the state of its sender and receiver.
     */
    private void handleEos() {
        if (throttledSender.eosApplied && throttledReceiver.eosReceived) {
            closeSilently(null); // a successful close
        }
    }

    /**
     * Called by {@link RouplexTcpSelector} when the underlying channel just got connected to the remote endpoint.
     */
    void handleConnected() {
        handleOpen(null);

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onConnected(this);
        }
    }

    /**
     * Called by {@link RouplexTcpSelector} when the underlying channel just failed connection to the remote endpoint.
     */
    void handleConnectionFailed(@Nullable Exception optionalReason) {
        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onConnectionFailed(this, optionalReason);
        }
    }

    /**
     * Called by {@link RouplexTcpSelector} when the underlying channel just got disconnected from the remote endpoint.
     */
    boolean handleDisconnected(@Nullable Exception optionalReason) {
        boolean drainedChannels = throttledReceiver.eosReceived && throttledSender.eosApplied;

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onDisconnected(this, optionalReason, drainedChannels);
        }

        return drainedChannels;
    }

    protected final RouplexTcpServer rouplexTcpServer; // not null if this channel was created by a rouplexTcpServer
    protected final RouplexTcpClientListener rouplexTcpClientListener;

    protected ThrottledSender throttledSender;
    protected ThrottledReceiver throttledReceiver;

    /**
     * Construct an instance using a prepared {@link Builder} instance.
     *
     * @param builder
     *          the builder providing all needed info for creation of the client
     */
    RouplexTcpClient(Builder builder) {
        super(builder);

        this.rouplexTcpServer = null;
        this.rouplexTcpClientListener = builder.rouplexTcpClientListener;
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
    RouplexTcpClient(SocketChannel socketChannel, RouplexTcpSelector rouplexTcpSelector, RouplexTcpServer rouplexTcpServer) {
        super(socketChannel, rouplexTcpSelector);

        this.rouplexTcpServer = rouplexTcpServer;
        this.rouplexTcpClientListener = null;
    }

    /**
     * Start the connection sequence if the underlying channel is not already connected.
     *
     * @throws IOException
     *          if any problem arises
     */
    private void connectAsync() throws IOException {
        SocketChannel socketChannel = (SocketChannel) selectableChannel;

        socketChannel.configureBlocking(false);
        if (!socketChannel.isConnectionPending() && !socketChannel.isConnected()) {
            socketChannel.connect(((Builder) builder).remoteAddress);
        }

        rouplexTcpSelector.asyncRegisterTcpEndPoint(this);
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
        throttledSender = new ThrottledSender(selectionKey);
        throttledReceiver = new ThrottledReceiver(selectionKey);
    }

    /**
     * Hook (rather get) the {@link Sender} to use for sending bytes via this client. The throttle parameter will be
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
     * @return
     *          the sender to be used to send ByteBuffer payloads.
     */
    public Sender<ByteBuffer> hookSendChannel(Throttle throttle) {
        synchronized (lock) {
            if (throttledSender.throttle != null) {
                throw new IllegalStateException("Send channel already hooked.");
            }

            throttledSender.throttle = throttle;
            return throttledSender;
        }
    }

    /**
     * Hook (or set) the {@link Receiver} which will be receiving the bytes read by this client. Whenever there are
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
    public Throttle hookReceiveChannel(@Nullable Receiver<byte[]> receiver, boolean started) {
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
