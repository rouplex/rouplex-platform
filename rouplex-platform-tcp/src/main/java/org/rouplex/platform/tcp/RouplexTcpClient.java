package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSocketChannel;
import org.rouplex.nio.channels.spi.SSLSelector;
import org.rouplex.platform.rr.ReceiveChannel;
import org.rouplex.platform.rr.SendChannel;
import org.rouplex.platform.rr.Throttle;

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
public class RouplexTcpClient extends RouplexTcpChannel {
    private static final ByteBuffer EOS = ByteBuffer.allocate(0);

    public static class Builder extends RouplexTcpChannel.Builder<RouplexTcpClient, Builder> {
        Builder(RouplexTcpClient instance) {
            super(instance);
        }

        protected void checkCanBuild() {
            if (instance.remoteAddress == null) {
                throw new IllegalStateException("Missing value for remoteAddress");
            }
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

        @Override
        public RouplexTcpClient build() throws IOException {
            checkNotBuilt();
            checkCanBuild();

            RouplexTcpClient result = instance;
            instance = null;
            return result.connect();
        }
    }

    public static Builder newBuilder() {
        return new Builder(new RouplexTcpClient(null, null, null));
    }

    class ThrottledReceiver extends Throttle {
        private final Object receiveLock = new Object();

        private byte[] pendingReceive;
        @Nullable private ReceiveChannel<byte[]> receiveChannel;
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
            rouplexTcpBinder.pauseRead(selectionKey, Long.MAX_VALUE); // 295 million years
            return true;
        }

        @Override
        public void resume() {
            synchronized (receiveLock) {
                if (pendingReceive != null) {
                    // the user will be called receive(byte[]) with this lock acquired but that is safe since this is
                    // the only place the lock is used (and actually the only reason to have it is to protect from
                    // competing resume() calls coming from user)
                    if (!consumeSocketInput(pendingReceive)) {
                        return;
                    }

                    pendingReceive = null;
                }
            }

            // No worries here since spurious pauseReads collapse and get handled as one by the rouplexTcpBinder thread
            rouplexTcpBinder.pauseRead(selectionKey, 0);
        }

        boolean consumeSocketInput(byte[] payload) {
            if (rateLimitCurrentTimestamp != 0) {
                if (System.currentTimeMillis() > rateLimitCurrentTimestamp) {
                    rateLimitCurrentTimestamp = System.currentTimeMillis() + rateLimitMillis;
                    rateLimitBytes = 0;
                } else {
                    rateLimitCurrentBytes += payload.length;
                    if (rateLimitCurrentBytes > rateLimitBytes) {
                        rouplexTcpBinder.pauseRead(selectionKey, rateLimitCurrentTimestamp);
                    }
                }
            }

            if (receiveChannel != null) {
                if (!receiveChannel.receive(payload.length == 0 ? null : payload)) {
                    pendingReceive = payload;
                    return false;
                }
            }

            if (eosReceived = payload.length == 0) {
                handleEos();
            }

            return true;
        }
    }

    class ThrottledSender implements SendChannel<ByteBuffer> {
        private final LinkedList<ByteBuffer> writeBuffers = new LinkedList<ByteBuffer>();
        private long writeBuffersCap = 1000000;
        private long remaining = writeBuffersCap;
        @Nullable Throttle throttle;
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
                destination.put(source);
                return srcRemaining;
            }
        }

        @Override
        public boolean send(ByteBuffer payload) throws IOException {
            int writeSize;

            synchronized (lock) {
                if (eosReceived) {
                    return false;
                }

                if (payload == null) {
                    payload = EOS;
                    eosReceived = true;
                }

                paused = remaining < payload.remaining();
                writeSize = (int) (paused ? remaining : payload.remaining());
                remaining -= writeSize;
            }

            ByteBuffer writeBuffer;
            if (writeSize > 0) {
                // potentially costly operations in this block so we must perform outside the lock space especially
                // since the removeWriteBuffer is performed from the RouplexTcpBinder's single thread responsible to go
                // over all the channels monitored!!!
                writeBuffer = ByteBuffer.allocate(writeSize);
                transfer(payload, writeBuffer);
                writeBuffer.flip();
            } else if (payload == EOS) {
                writeBuffer = EOS;
            } else {
                writeBuffer = null;
            }

            // This section may seem weird, but we don't want to fire pause() before copying any of the content from
            // payload to writeBuffer first.
            if (paused && throttle != null) {
                throttle.pause(); // caller's thread, no need for try/catch protection
            }

            if (writeBuffer != null) {
                synchronized (lock) {
                    writeBuffers.add(writeBuffer);
                }

                rouplexTcpBinder.addInterestOps(selectionKey, SelectionKey.OP_WRITE);
            }

            return !paused;
        }

        ByteBuffer pollFirstWriteBuffer() {
            synchronized (lock) {
                return writeBuffers.isEmpty() ? null : writeBuffers.iterator().next();
            }
        }

        void removeWriteBuffer(ByteBuffer writeBuffer) {
            boolean fromPaused;

            synchronized (lock) {
                writeBuffers.remove(writeBuffer);
                remaining += writeBuffer.limit();

                if (fromPaused = paused) { // assignment, not comparison
                    paused = false;
                }
            }

            if (fromPaused) {
                throttle.resume();
            }

            if (writeBuffer == EOS) {
                eosApplied = true;
                handleEos();
            }
        }
    }

    private void handleEos() {
        synchronized (lock) {
            if (throttledSender.eosApplied && throttledReceiver.eosReceived) {
                rouplexTcpBinder.closeChannel(this);
            }
        }
    }

    protected SocketAddress remoteAddress;
    final ThrottledSender throttledSender = new ThrottledSender();
    final ThrottledReceiver throttledReceiver = new ThrottledReceiver();
    final RouplexTcpServer rouplexTcpServer;

    RouplexTcpClient(SelectableChannel selectableChannel, RouplexTcpBinder rouplexTcpBinder, RouplexTcpServer rouplexTcpServer) {
        super(selectableChannel, rouplexTcpBinder);

        this.rouplexTcpServer = rouplexTcpServer;
    }

    private RouplexTcpClient connect() throws IOException {
        if (rouplexTcpBinder == null) {
            rouplexTcpBinder = new RouplexTcpBinder(sslContext == null ? Selector.open() : SSLSelector.open(), null);
        }

        if (selectableChannel == null) {
            selectableChannel = sslContext == null
                    ? SocketChannel.open(remoteAddress) : SSLSocketChannel.open(remoteAddress, sslContext);
        }

        rouplexTcpBinder.addChannel(this);
        return this;
    }

    /**
     * Hook (rather get) the channel which would be used to send bits.
     *
     * The provided throttle will be used by us, in case we need to pause the sends (writes) because the buffers
     * may fill up.
     *
     * @param throttle
     *          A throttle construct which we would use to pause send requests
     * @return
     *          The channel to be used to send the bits.
     */
    public SendChannel<ByteBuffer> hookSendChannel(Throttle throttle) {
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
     * @param receiveChannel
     *          The channel receiving the bits
     * @return
     *          The throttle construct which the receiver can use to throttle the flow of receiving bits.
     */
    public Throttle hookReceiveChannel(@Nullable ReceiveChannel<byte[]> receiveChannel) {
        synchronized (lock) {
            if (throttledReceiver.receiveChannel != null) {
                throw new IllegalStateException("Receive channel already hooked.");
            }

            throttledReceiver.receiveChannel = receiveChannel;
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
