package org.rouplex.platform.tcp;

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
import java.nio.channels.SocketChannel;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpClient extends RouplexTcpEndPoint {
    protected final static byte[] EOS_BA = new byte[0];
    private static final ByteBuffer EOS_BB = ByteBuffer.allocate(0);

    public static class Builder extends RouplexTcpEndPoint.Builder<RouplexTcpClient, Builder> {
        protected SocketAddress remoteAddress;
        protected RouplexTcpClientListener rouplexTcpClientListener;

        protected void checkCanBuild() {
            if (remoteAddress == null) {
                throw new IllegalStateException("Missing value for remoteAddress");
            }
        }

        public Builder withSocketChannel(SocketChannel socketChannel) {
            checkNotBuilt();

            this.selectableChannel = socketChannel;
            return builder;
        }

        public Builder withRemoteAddress(SocketAddress remoteAddress) {
            checkNotBuilt();
            this.remoteAddress = remoteAddress;
            return builder;
        }

        public Builder withRemoteAddress(String hostname, int port) {
            checkNotBuilt();
            this.remoteAddress = new InetSocketAddress(hostname, port);
            return builder;
        }

        public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) throws IOException {
            checkNotBuilt();

            this.sslContext = secure ? sslContext != null ? sslContext : buildRelaxedSSLContext() : null;
            return builder;
        }

        public Builder withRouplexTcpClientListener(RouplexTcpClientListener rouplexTcpClientListener) {
            checkNotBuilt();

            this.rouplexTcpClientListener = rouplexTcpClientListener;
            return builder;
        }

        @Override
        public RouplexTcpClient buildAsync() throws IOException {
            checkNotBuilt();
            checkCanBuild();

            if (selectableChannel == null) {
                selectableChannel = sslContext == null
                        ? SocketChannel.open() : SSLSocketChannel.open(sslContext);
            }

            RouplexTcpClient result = new RouplexTcpClient(this);
            builder = null;
            result.connectAsync();
            return result;
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    class ThrottledReceiver extends Throttle {
        @Nullable
        private Receiver<byte[]> receiver;
        boolean eosReceived;

        private long rateLimitCurrentTimestamp;
        private long rateLimitCurrentBytes;
        private long rateLimitBytes;
        private long rateLimitMillis;

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

    class ThrottledSender implements Sender<ByteBuffer> {
        private final LinkedList<ByteBuffer> writeBuffers = new LinkedList<ByteBuffer>();
        private long remaining;
        @Nullable
        Throttle throttle;
        boolean paused;
        boolean eosReceived;
        boolean eosApplied;

        ThrottledSender() {
            try {
                remaining = ((SocketChannel) selectableChannel).socket().getSendBufferSize();
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

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

    private void handleEos() {
        if (throttledSender.eosApplied && throttledReceiver.eosReceived) {
            closeSilently(null); // a successful close
        }
    }

    void handleConnected() {
        updateOpen(null);

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onConnected(this);
        }
    }

    void handleConnectionFailed(@Nullable Exception optionalReason) {
        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onConnectionFailed(this, optionalReason);
        }
    }

    boolean handleDisconnected(@Nullable Exception optionalReason) {
        boolean drainedChannels = throttledReceiver.eosReceived && throttledSender.eosApplied;

        if (rouplexTcpClientListener != null) {
            rouplexTcpClientListener.onDisconnected(this, optionalReason, drainedChannels);
        }

        return drainedChannels;
    }

    protected final RouplexTcpServer rouplexTcpServer; // if this channel was created by a rouplexTcpServer
    protected final RouplexTcpClientListener rouplexTcpClientListener;

    protected ThrottledSender throttledSender;
    protected ThrottledReceiver throttledReceiver;

    RouplexTcpClient(Builder builder) {
        super(builder);

        this.rouplexTcpServer = null;
        this.rouplexTcpClientListener = builder.rouplexTcpClientListener;
    }

    RouplexTcpClient(SocketChannel socketChannel, RouplexTcpSelector rouplexTcpSelector, RouplexTcpServer rouplexTcpServer) {
        super(socketChannel, rouplexTcpSelector);

        this.rouplexTcpServer = rouplexTcpServer;
        this.rouplexTcpClientListener = null;
    }

    private void connectAsync() throws IOException {
        SocketChannel socketChannel = (SocketChannel) selectableChannel;

        socketChannel.configureBlocking(false);
        if (!socketChannel.isConnectionPending() && !socketChannel.isConnected()) {
            socketChannel.connect(((Builder) builder).remoteAddress);
        }

        rouplexTcpSelector.asyncRegisterTcpEndPoint(this);
    }

    public SocketAddress getRemoteAddress(boolean resolved) throws IOException {
        synchronized (lock) {
            if (isClosed()) {
                throw new IOException("Already closed");
            }

            SocketAddress result = selectableChannel != null
                    ? ((SocketChannel) selectableChannel).getRemoteAddress() : null;

            if (result != null) {
                return result;
            }

            if (resolved) {
                throw new IOException("Not connected yet");
            }

            return builder != null ? ((Builder) builder).remoteAddress : null;
        }
    }

    @Override
    void setSelectionKey(SelectionKey selectionKey) {
        synchronized (lock) {
            super.setSelectionKey(selectionKey);

            throttledSender = new ThrottledSender();
            throttledReceiver = new ThrottledReceiver();
        }
    }

    /**
     * Hook (rather get) the channel which would be used to handleRequest bits.
     * <p>
     * The provided throttle will be used by us, in case we need to pause the sends (writes) because the buffers
     * may fill up.
     *
     * @param throttle
     *         A throttle construct which we would use to pause handleRequest requests
     * @return The channel to be used to handleRequest the bits.
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
     * Hook the receive channel which would be getting all the received bits.
     * The receiver can use the Throttle returned to notify us to pause (and then later, to resume).
     *
     * @param receiver
     *         The channel receiving the bits
     * @return The throttle construct which the receiver can use to throttle the flow of receiving bits.
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
     * The local server this client belongs to, or null if this client in not related to a local server.
     *
     * @return
     */
    public RouplexTcpServer getRouplexTcpServer() {
        return rouplexTcpServer;
    }

    /**
     * Convenience method for building client side {@link SSLContext} instances that do not perform any kind of
     * authorization.
     *
     * @return
     * @throws Exception
     */
    public static SSLContext buildRelaxedSSLContext() throws IOException {
        TrustManager tm = new X509TrustManager() {
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

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{tm}, null);
            return sslContext;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
