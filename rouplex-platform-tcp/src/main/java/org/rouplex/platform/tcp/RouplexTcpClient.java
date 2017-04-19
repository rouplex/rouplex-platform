package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.NotNull;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSelector;
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
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpClient extends RouplexTcpEndPoint {
    static final ByteBuffer EOS = ByteBuffer.allocate(0);
    protected RouplexTcpClientListener rouplexTcpClientListener;

    public static class Builder extends RouplexTcpEndPoint.Builder<RouplexTcpClient, Builder> {
        Builder(RouplexTcpClient instance) {
            super(instance);
        }

        protected void checkCanBuild() {
            if (instance.remoteAddress == null) {
                throw new IllegalStateException("Missing value for remoteAddress");
            }
        }

        public Builder withSocketChannel(SocketChannel socketChannel) {
            checkNotBuilt();

            instance.selectableChannel = socketChannel;
            return builder;
        }

        public Builder withRemoteAddress(SocketAddress remoteAddress) {
            checkNotBuilt();
            instance.remoteAddress = remoteAddress;
            return builder;
        }

        public Builder withRemoteAddress(String hostname, int port) {
            checkNotBuilt();
            instance.remoteAddress = new InetSocketAddress(hostname, port);
            return builder;
        }

        public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) throws IOException {
            checkNotBuilt();

            instance.sslContext = secure ? sslContext != null ? sslContext : buildRelaxedSSLContext() : null;
            return builder;
        }

        public Builder withRouplexTcpClientListener(RouplexTcpClientListener rouplexTcpClientListener) {
            checkNotBuilt();

            instance.rouplexTcpClientListener = rouplexTcpClientListener;
            return builder;
        }

        @Override
        public RouplexTcpClient buildAsync() throws IOException {
            checkNotBuilt();
            checkCanBuild();

            RouplexTcpClient result = instance;
            instance = null;
            result.connectAsync();
            return result;
        }
    }

    public static Builder newBuilder() {
        return new Builder(new RouplexTcpClient(null, null, null));
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
            rouplexTcpBinder.asyncPauseRead(selectionKey, Long.MAX_VALUE); // 295 million years
            return true;
        }

        @Override
        public void resume() {
            rouplexTcpBinder.asyncResumeRead(selectionKey);
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
                        rouplexTcpBinder.asyncPauseRead(selectionKey, rateLimitCurrentTimestamp);
                    }
                }
            }

            if (eosReceived = payload == RouplexTcpBinder.EOS) {
                handleEos();
            }

            return consumed;
        }
    }

    class ThrottledSender implements Sender<ByteBuffer> {
        private final LinkedList<ByteBuffer> writeBuffers = new LinkedList<ByteBuffer>();
        private long remaining = sendBufferSize != 0 ? sendBufferSize : 256 * 1024;
        @Nullable
        Throttle throttle;
        boolean paused;
        boolean eosReceived;
        boolean eosApplied;

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
                if (eosReceived || isClosed()) {
                    throw new IOException("Sender is closed");
                }

                if (payload == null) { // null is marker for client (abrupt) close
                    close();
                    return;
                }

                if (!payload.hasRemaining()) { // empty buffer is marker for channel (graceful) close
                    payload = EOS;
                    eosReceived = true;
                } else if (paused) { // don't remove else keyword since we must always accept the EOS for delivery
                    return;
                }

                paused = remaining < payload.remaining();
                writeSize = (int) (paused ? remaining : payload.remaining());
                remaining -= writeSize;
            }

            ByteBuffer writeBuffer;
            if (payload != EOS) {
                // potentially costly operations in this block so we must perform outside the lock space especially
                // since the removeWriteBuffer is performed from the RouplexTcpBinder's single thread responsible to go
                // over all the channels monitored!!!
                writeBuffer = ByteBuffer.allocate(writeSize);
                transfer(payload, writeBuffer);
                writeBuffer.flip();
            } else {
                writeBuffer = EOS;
            }

            Throttle throttle;
            synchronized (lock) {
                writeBuffers.add(writeBuffer);
                throttle = paused ? this.throttle : null;
            }

            rouplexTcpBinder.asyncResumeWrite(selectionKey);

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

            if (writeBuffer == EOS) {
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

    protected SocketAddress remoteAddress;
    protected ThrottledSender throttledSender;
    protected ThrottledReceiver throttledReceiver;
    protected RouplexTcpServer rouplexTcpServer; // if this channel was created by a rouplexTcpServer

    RouplexTcpClient(SelectableChannel selectableChannel, RouplexTcpBinder rouplexTcpBinder, RouplexTcpServer rouplexTcpServer) {
        super(selectableChannel, rouplexTcpBinder);

        this.rouplexTcpServer = rouplexTcpServer;
    }

    private void connectAsync() throws IOException {
        if (rouplexTcpBinder == null) {
            rouplexTcpBinder = new RouplexTcpBinder(sslContext == null ? Selector.open() : SSLSelector.open(), null);
        }

        SocketChannel socketChannel;
        if (selectableChannel != null) {
            socketChannel = (SocketChannel) selectableChannel;
        } else {
            socketChannel = sslContext == null ? SocketChannel.open() : SSLSocketChannel.open(sslContext);
            selectableChannel = socketChannel;
        }

        if (sendBufferSize != 0) {
            socketChannel.socket().setSendBufferSize(sendBufferSize);
        }
        if (receiveBufferSize != 0) {
            socketChannel.socket().setReceiveBufferSize(receiveBufferSize);
        }

        socketChannel.configureBlocking(false);
        if (!socketChannel.isConnectionPending() && !socketChannel.isConnected()) {
            socketChannel.connect(remoteAddress);
        }

        rouplexTcpBinder.asyncRegisterTcpEndPoint(this);
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

            return remoteAddress;
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
