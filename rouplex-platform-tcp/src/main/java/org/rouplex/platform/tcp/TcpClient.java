package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSocketChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeoutException;

/**
 * A class representing a TCP client that connects to a remote endpoint and optionally binds to a local one.
 *
 * Instances of this class are obtained via the builder obtainable in its turn via a call to
 * {@link TcpReactor#newTcpClientBuilder()}.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpClient extends TcpEndPoint {
    /**
     * A TcpClient builder. The builder can only build one client, and once done, any future calls to alter the builder
     * or try to rebuild will fail with {@link IllegalStateException}.
     */
    public static class Builder extends TcpEndPoint.Builder<TcpClient, Builder> {
        protected SocketAddress remoteAddress;
        protected String remoteHost;
        protected int remotePort;
        protected TcpClientLifecycleListener tcpClientLifecycleListener;
        protected int connectTimeoutMillis = -1;

        Builder(TcpReactor tcpReactor) {
            super(tcpReactor);
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
         *          A socket channel, in connected or just in open (and not connected) state
         * @return
         *          The reference to this builder for chaining calls
         */
        synchronized public Builder withSocketChannel(SocketChannel socketChannel) {
            checkNotBuilt();

            this.selectableChannel = socketChannel;
            return builder;
        }

        /**
         * A remote {@link SocketAddress} where to connect to.
         *
         * @param remoteAddress
         *          The remote address to connect to
         * @return
         *          The reference to this builder for chaining calls
         */
        synchronized public Builder withRemoteAddress(SocketAddress remoteAddress) {
            checkNotBuilt();

            this.remoteAddress = remoteAddress;
            return builder;
        }

        /**
         * A remote host name and port, representing a remote address to connect to.
         *
         * @param hostname
         *          The name of the host to connect to
         * @param port
         *          The remote port to connect to
         * @return
         *          The reference to this builder for chaining calls
         */
        synchronized public Builder withRemoteAddress(String hostname, int port) {
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
         *          True if the client should connect securely to the remote endpoint
         * @param sslContext
         *          The sslContext to use, or null if an allow-all is preferred
         * @return
         *          The reference to this builder for chaining calls
         */
        synchronized public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) {
            checkNotBuilt();

            this.sslContext = secure ? sslContext != null ? sslContext : TcpClient.buildRelaxedSSLContext() : null;
            return builder;
        }

        /**
         * The number of milliseconds for the connection to succeed. Otherwise it will time out.
         *
         * @param connectTimeoutMillis
         *          -1: (default) the connection will be performed asynchronously hence no timeout will be enforced.
         *           0: indefinite timeout, so the call to connect will block till connection succeeds or fails.
         *          >0: block for up to connectTimeoutMillis milliseconds waiting for connection to succeed or throw
         *              {@link TimeoutException}
         * @return
         *          The builder for chaining other settings or build the TcpClient.
         */
        synchronized public Builder withConnectTimeout(int connectTimeoutMillis) {
            checkNotBuilt();

            this.connectTimeoutMillis = connectTimeoutMillis == 0 ? Integer.MAX_VALUE : connectTimeoutMillis;
            return builder;
        }

        /**
         * Set the client lifecycle event listener.
         *
         * @param tcpClientLifecycleListener
         *          The event listener
         * @return
         *          The reference to this builder for chaining calls
         */
        synchronized public Builder withTcpClientLifecycleListener(TcpClientLifecycleListener tcpClientLifecycleListener) {
            checkNotBuilt();

            this.tcpClientLifecycleListener = tcpClientLifecycleListener;
            return builder;
        }

        /**
         * Build the client and return it.
         *
         * @return
         *          The built but unconnected client
         * @throws
         *          IOException if any problems arise during the client creation and connection initialization
         */
        @Override
        synchronized public TcpClient build() throws IOException {
            checkNotBuilt();
            checkCanBuild();
            builder = null;

            if (selectableChannel == null) {
                selectableChannel = sslContext == null ? SocketChannel.open()
                    : SSLSocketChannel.open(sslContext, remoteHost, remotePort, true, null, null);
            }

            return new TcpClient(this);
        }
    }

    private final Thread tcpSelectorThread;
    private final TcpServer originatingTcpServer; // not null if this channel was created by a originatingTcpServer
    private final TcpClientLifecycleListener tcpClientLifecycleListener;

    private Builder builder;
    private int currentInterestOps;
    private boolean unregistrationHandled;

    // Both fields accessed by the respective channels
    final TcpReadChannel tcpReadChannel;
    final TcpWriteChannel tcpWriteChannel;

    // Accessed by TcpSelector to optimize by removing this key from selectedKeys set and not handle it twice
    SelectionKey selectionKey;

    /**
     * Construct an instance using a prepared {@link TcpEndPoint.Builder} instance.
     *
     * @param builder
     *          The builder providing all needed info for creation of the client
     */
    TcpClient(Builder builder) throws IOException {
        super(builder.selectableChannel, builder.tcpSelector, builder.attachment);

        tcpSelectorThread = builder.tcpSelector.tcpSelectorThread;
        originatingTcpServer = null;
        tcpClientLifecycleListener = builder.tcpClientLifecycleListener;
        tcpReadChannel = new TcpReadChannel(this);
        tcpWriteChannel = new TcpWriteChannel(this);
        this.builder = builder;
    }

    /**
     * Construct an instance by wrapping a {@link SocketChannel} obtained via a {@link ServerSocketChannel#accept()}.
     *
     * @param socketChannel
     *          The underlying channel to use for reading and writing to the network
     * @param tcpSelector
     *          The tcpSelector wrapping the {@link Selector} used to register the channel
     * @param originatingTcpServer
     *          The originatingTcpServer which accepted the underlying channel
     */
    TcpClient(SocketChannel socketChannel, TcpSelector tcpSelector, TcpServer originatingTcpServer) throws IOException {
        super(socketChannel, tcpSelector, null);

        tcpSelectorThread = tcpSelector.tcpSelectorThread;
        this.originatingTcpServer = originatingTcpServer;
        this.tcpClientLifecycleListener = originatingTcpServer.tcpClientLifecycleListener;
        tcpReadChannel = new TcpReadChannel(this);
        tcpWriteChannel = new TcpWriteChannel(this);
    }

    public void connect() throws IOException {
        long expirationTimestamp;

        synchronized (lock) {
            if (builder == null) {
                throw new IOException("Client is already " + (open ? "connected" : closed ? "closed" : "connecting"));
            }

            if (builder.connectTimeoutMillis != -1 &&
                tcpSelector.tcpReactor.tcpSelectorThreads.contains(tcpSelectorThread)) {
                throw new IOException("Cannot perform blocking connect from a TcpReactor thread");
            }

            SocketChannel socketChannel = (SocketChannel) selectableChannel;
            if (!socketChannel.isConnectionPending() && !socketChannel.isConnected()) {
                socketChannel.configureBlocking(false);
                socketChannel.connect(builder.remoteAddress);
            }

            expirationTimestamp = builder.connectTimeoutMillis == -1
                ? -1 : System.currentTimeMillis() + builder.connectTimeoutMillis;

            builder = null;
        }

        tcpSelector.asyncRegisterTcpEndPoint(this);

        if (expirationTimestamp > 0) {
            waitForOpen(expirationTimestamp);
        }
    }

    public Socket getSocket() {
        return ((SocketChannel) selectableChannel).socket();
    }

    /**
     * Get the remote endpoint address where this instance is connected, or is connecting to.
     *
     * @return
     *          The remote endpoint address
     * @throws
     *          IOException if the instance is already closed or any other problem retrieving the remote address
     */
    public SocketAddress getRemoteAddress() throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IOException("Already closed");
            }

            // jdk1.7+ return ((SocketChannel) selectableChannel).getRemoteAddress();
            return ((SocketChannel) selectableChannel).socket().getRemoteSocketAddress();
        }
    }

    public TcpReadChannel getReadChannel() {
        return tcpReadChannel;
    }

    public TcpWriteChannel getWriteChannel() {
        return tcpWriteChannel;
    }

    /**
     * Get the local {@link TcpServer} instance this client belongs to, or null if this client was not obtained via a
     * local tcp server.
     *
     * @return
     *          The TcpServer this client belongs to, if any
     */
    public TcpServer getOriginatingTcpServer() {
        return originatingTcpServer;
    }

    void handleRegistration() throws Exception {
        if (originatingTcpServer != null) {
            selectionKey = selectableChannel.register(tcpSelector.selector, 0, this);
            handleConnected();
        } else {
            selectionKey = selectableChannel.register(tcpSelector.selector, SelectionKey.OP_CONNECT, this);
        }
    }

    void updateInterestOps() {
        int interestOps = tcpReadChannel.channelReadyCallback != null ? SelectionKey.OP_READ : 0;
        if (tcpWriteChannel.channelReadyCallback != null) {
            interestOps |= SelectionKey.OP_WRITE;
        }

        if (this.currentInterestOps != interestOps) {
            try {
                // Debug: tcpSelector.tcpReactor.tcpMetrics.interestOpsCount.inc();
                selectionKey.interestOps(this.currentInterestOps = interestOps);
            } catch (Exception e) { // CancelledKeyException
                // most likely nothing
            }
        }
    }

    void handleOpsReady() throws Exception {
        if (selectionKey.isConnectable()) {
            if (!((SocketChannel) selectionKey.channel()).finishConnect()) {
                return;
            }

            selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_CONNECT);
            handleConnected();
        }

        if (selectionKey.isReadable()) {
            tcpReadChannel.handleChannelReady();
        }

        if (selectionKey.isWritable()) {
            tcpWriteChannel.handleChannelReady();
        }
    }

    /**
     * Called by {@link TcpSelector} when the underlying channel just got connected to the remote endpoint.
     */
    private void handleConnected() {
        handleOpen();

        if (tcpClientLifecycleListener != null) {
            tcpClientLifecycleListener.onConnected(this);
        }
    }

    /**
     * Called by {@link TcpSelector} when the underlying channel just got disconnected from the remote endpoint.
     * Always called from the same thread, which is also the one updating eosReceived and eosApplied, so no need for
     * synchronization here.
     */
    @Override
    void handleUnregistration(@Nullable Exception optionalReason) {
        // it is possible that this method gets called twice within the same selector loop, once with a selection and
        // once with handlePreSelectUpdates, so avoid firing twice. unregistrationHandled needs no syncing since
        // it is only accessed from same selector's thread.
        if (unregistrationHandled) {
            return;
        }

        unregistrationHandled = true;
        setExceptionAndCloseChannel(optionalReason);

        try {
            // give user of channel a chance to catch the failed state
            tcpReadChannel.handleChannelReady();
        } catch (RuntimeException e) {
            // RuntimeException (from client handling notification)
        }

        try {
            // give user of channel a chance to catch the failed state
            tcpWriteChannel.handleChannelReady();
        } catch (RuntimeException e) {
            // RuntimeException (from client handling notification)
        }

        try {
            if (tcpClientLifecycleListener != null) {
                if (open) {
                    tcpClientLifecycleListener.onDisconnected(this, ioException);
                } else {
                    tcpClientLifecycleListener.onConnectionFailed(this, ioException);
                }
            }
        } catch (RuntimeException re) {
            // RuntimeException (from client handling notification)
        }
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
     *          A relaxed sslContext
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
