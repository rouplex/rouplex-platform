package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSocketChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.*;
import java.util.concurrent.TimeoutException;

/**
 * A class representing a TCP client that connects to a remote endpoint and optionally binds to a local one.

 * Instances of this class are obtained via the {@link TcpClient.Builder}
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpClient extends TcpEndPoint {

    public static class Builder extends TcpClientBuilder<TcpClient, Builder> {
        public Builder(TcpReactor tcpReactor) {
            super(tcpReactor);
        }

        /**
         * Build the client and return it.
         *
         * @return The built but unconnected client
         * @throws Exception if any problems arise during the client creation and connection initialization
         */
        @Override
        public TcpClient build() throws Exception {
            return buildTcpClient();
        }
    }

    /**
     * A TcpClient builder. The builder can only build one client, and once done, any future calls to alter the builder
     * or try to rebuild will fail with {@link IllegalStateException}.
     * <p/>
     * Not thread safe.
     */
    protected abstract static class TcpClientBuilder<T, B extends TcpClientBuilder> extends TcpEndPointBuilder<T, B> {
        protected SocketAddress remoteAddress;
        protected String remoteHost;
        protected int remotePort;
        protected TcpClientListener tcpClientListener;
        protected int connectTimeoutMillis = -1;

        protected TcpClientBuilder(TcpReactor tcpReactor) {
            super(tcpReactor);
        }

        /**
         * An optional {@link SocketChannel}. May be already connecting or connected, in which case the localAddress,
         * remoteAddress, and {@link SSLContext} cannot be set anymore.
         *
         * @param socketChannel
         *          A socket channel, in any non-closed state (unconnected, connecting, connected)
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withSocketChannel(SocketChannel socketChannel) {
            checkNotBuilt();
            if (sslContext != null) {
                throw new IllegalStateException("SslContext is already set and cannot coexist with SocketChannel");
            }

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
        public B withRemoteAddress(SocketAddress remoteAddress) {
            checkNotBuilt();
            checkRemoteAddressSettable();

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
        public B withRemoteAddress(String hostname, int port) {
            checkNotBuilt();
            checkRemoteAddressSettable();

            this.remoteHost = hostname;
            this.remotePort = port;
            this.remoteAddress = new InetSocketAddress(hostname, port);

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
        public B withConnectTimeout(int connectTimeoutMillis) {
            checkNotBuilt();

            this.connectTimeoutMillis = connectTimeoutMillis == 0 ? Integer.MAX_VALUE : connectTimeoutMillis;
            return builder;
        }

        /**
         * Set the client lifecycle event listener.
         *
         * @param tcpClientListener
         *          The event listener
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withTcpClientListener(TcpClientListener tcpClientListener) {
            checkNotBuilt();

            this.tcpClientListener = tcpClientListener;
            return builder;
        }

        @Override
        protected void checkLocalAddressSettable() {
            if (selectableChannel != null && ((SocketChannel) selectableChannel).socket().isBound()) {
                throw new IllegalStateException("ServerSocketChannel is already bound and LocalAddress cannot be set anymore");
            }
        }

        protected void checkRemoteAddressSettable() {
            if (selectableChannel == null) {
                return;
            }

            SocketChannel socketChannel = ((SocketChannel) selectableChannel);
            if (socketChannel.isConnectionPending()) {
                throw new IllegalStateException("SocketChannel is already connecting and RemoteAddress cannot be set anymore");
            }

            if (socketChannel.isConnected()) {
                throw new IllegalStateException("SocketChannel is already connected and RemoteAddress cannot be set anymore");
            }
        }

        @Override
        protected void checkCanBuild() {
            super.checkCanBuild();

            if (remoteAddress == null) {
                throw new IllegalStateException("Missing value for remoteAddress");
            }
        }

        @Override
        protected void prepareBuild() throws Exception {
            super.prepareBuild();

            if (selectableChannel == null) {
                selectableChannel = sslContext == null
                        ? SocketChannel.open()
                        : SSLSocketChannel.open(sslContext, remoteHost, remotePort, true, null, null);
            }
        }

        protected TcpClient buildTcpClient() throws Exception {
            prepareBuild();
            return new TcpClient(this);
        }
    }

    protected final Thread tcpSelectorThread;
    protected final TcpServer originatingTcpServer; // not null if this channel was created by a originatingTcpServer
    protected final TcpClientListener tcpClientListener;

    // Both fields accessed by the respective channels on this package
    protected final TcpReadChannel tcpReadChannel;
    protected final TcpWriteChannel tcpWriteChannel;

    @GuardedBy("no-need-guarding") private int interestOps;
    @GuardedBy("no-need-guarding") private boolean unregistrationHandled;

    // Accessed by TcpSelector to optimize by removing this key from selectedKeys set and not handle it twice
    @GuardedBy("no-need-guarding") SelectionKey selectionKey;

    /**
     * Construct an instance using a prepared {@link TcpClientBuilder} instance.
     *
     * @param builder The builder providing all needed info for creation of the client
     */
    protected TcpClient(TcpClientBuilder builder) {
        this(builder.selectableChannel, builder.tcpSelector, builder, null, builder.tcpClientListener);
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
    protected TcpClient(SocketChannel socketChannel, TcpReactor.TcpSelector tcpSelector, TcpServer originatingTcpServer) {
        this(socketChannel, tcpSelector, originatingTcpServer.builder,
                originatingTcpServer, originatingTcpServer.tcpClientListener);
    }

    /**
     * Factored constructor
     *
     * @param selectableChannel
     *          The underlying channel to use for accepting, reading and writing to the network
     * @param tcpSelector
     *          The tcpSelector wrapping the {@link Selector} used to register the channel
     * @param builder
     *          The builder to be used for additional settings inherited from {@link TcpServer}
     */
    private TcpClient(SelectableChannel selectableChannel, TcpReactor.TcpSelector tcpSelector,
                      TcpEndPointBuilder builder, TcpServer originatingTcpServer, TcpClientListener tcpClientListener) {
        super(selectableChannel, tcpSelector, builder);

        this.originatingTcpServer = originatingTcpServer;
        this.tcpClientListener = tcpClientListener;

        tcpSelectorThread = tcpSelector.tcpSelectorThread;
        tcpReadChannel = new TcpReadChannel(this);
        tcpWriteChannel = new TcpWriteChannel(this);
    }

    public void connect() throws Exception {
        if (originatingTcpServer != null) {
            throw new IOException("Functionality not available for clients representing server side sessions");
        }

        long expirationTimestamp;
        synchronized (lock) {
            if (builder == null) {
                throw new IOException("Client is already " + (open ? "connected" : closed ? "closed" : "connecting"));
            }

            TcpClientBuilder tcpClientBuilder = (TcpClientBuilder) builder;
            builder = null;

            SocketChannel socketChannel = (SocketChannel) selectableChannel;
            if (!socketChannel.isConnectionPending() && !socketChannel.isConnected()) {
                socketChannel.configureBlocking(false);
                socketChannel.connect(tcpClientBuilder.remoteAddress);
            }

            expirationTimestamp = tcpClientBuilder.connectTimeoutMillis == -1
                    ? -1 : System.currentTimeMillis() + tcpClientBuilder.connectTimeoutMillis;
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
     * @return The remote endpoint address
     * @throws IOException if the instance is already closed or any other problem retrieving the remote address
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
     * @return The TcpServer this client belongs to, if any
     */
    public TcpServer getOriginatingTcpServer() {
        return originatingTcpServer;
    }

    void syncHandleRegistration() {
        try {
            if (originatingTcpServer != null) {
                selectionKey = selectableChannel.register(tcpSelector.selector, 0, this);
                syncHandleConnected();
            } else {
                selectionKey = selectableChannel.register(tcpSelector.selector, SelectionKey.OP_CONNECT, this);
            }
        } catch (Exception e) {
            handleException(AutoCloseCondition.ON_CHANNEL_EXCEPTION, e, true);
        }
    }

    void syncUpdateInterestOps() {
        int newInterestOps = tcpReadChannel.channelReadyCallback != null ? SelectionKey.OP_READ : 0;
        if (tcpWriteChannel.channelReadyCallback != null) {
            newInterestOps |= SelectionKey.OP_WRITE;
        }

        if (this.interestOps != newInterestOps) {
            try {
                // Debug: tcpSelector.tcpReactor.tcpMetrics.interestOpsCount.inc();
                selectionKey.interestOps(this.interestOps = newInterestOps);
            } catch (Exception e) { // CancelledKeyException
                /**
                 * {@link SelectionKey#isValid()} has returned true prior to calling syncUpdateInterestOps, but since
                 * the channel can be closed at any time from a concurrent call, we may catch
                 * {@link CancellationException} here, in which case, we go on with unregistering the chanel if not
                 * already registered
                 */
                handleException(AutoCloseCondition.ON_CHANNEL_EXCEPTION, e, true);
            }
        }
    }

    void syncHandleOpsReady() {
        try {
            int readyOps = selectionKey.readyOps();

            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                if (!((SocketChannel) selectionKey.channel()).finishConnect()) {
                    return;
                }

                selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_CONNECT);
                syncHandleConnected();
            }

            if ((readyOps & SelectionKey.OP_READ) != 0) {
                tcpReadChannel.syncHandleChannelReady();
            }

            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                tcpWriteChannel.syncHandleChannelReady();
            }
        } catch (Exception e) {
            handleException(AutoCloseCondition.ON_CHANNEL_EXCEPTION, e, true);
        }
    }

    /**
     * Called by {@link TcpReactor.TcpSelector} when the underlying channel just got connected to the remote endpoint.
     * This method must not throw any exceptions (including RuntimeException)
     */
    private void syncHandleConnected() {
        syncHandleOpen();

        if (tcpClientListener == null) {
            return;
        }

        Runnable onConnected = new Runnable() {
            @Override
            public void run() {
                try {
                    tcpClientListener.onConnected(TcpClient.this);
                } catch (RuntimeException re) {
                    handleException(AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, false);
                }
            }
        };

        if (eventsExecutor == null) {
            onConnected.run();
        } else {
            eventsExecutor.execute(onConnected);
        }
    }

    /**
     * Called by {@link TcpReactor.TcpSelector} when the underlying channel just got disconnected from the remote
     * endpoint.
     */
    @Override
    void syncHandleUnregistration(@Nullable Exception optionalReason) {
        // it is possible that this method gets called twice within the same selector loop, once with a selection and
        // once with handlePreSelectUpdates, so avoid firing twice. unregistrationHandled needs no syncing since
        // it is only accessed from same selector's thread.
        if (unregistrationHandled) {
            return;
        }

        unregistrationHandled = true;

        synchronized (lock) {
            if (finalException == null) {
                finalException = optionalReason;
                lock.notifyAll();
            }
        }

        try {
            // give user of channel a chance to catch the failed state
            if (tcpReadChannel.channelReadyCallback != null) {
                tcpReadChannel.syncHandleChannelReady();
            }
        } catch (RuntimeException e) {
            // RuntimeException from client handling notification -- nothing we can do at this stage
        }

        try {
            // give user of channel a chance to catch the failed state
            if (tcpWriteChannel.channelReadyCallback != null) {
                tcpWriteChannel.syncHandleChannelReady();
            }
        } catch (RuntimeException e) {
            // RuntimeException from client handling notification -- nothing we can do at this stage
        }

        if (tcpClientListener == null) {
            return;
        }

        if (open) {
            Runnable onDisconnected = new Runnable() {
                @Override
                public void run() {
                    try {
                        tcpClientListener.onDisconnected(TcpClient.this, finalException);
                    } catch (RuntimeException re) {
                        // RuntimeException from client handling notification -- nothing we can do at this stage
                    }
                }
            };

            if (eventsExecutor == null) {
                onDisconnected.run();
            } else {
                eventsExecutor.execute(onDisconnected);
            }

            return;
        }

        Runnable onConnectionFailed = new Runnable() {
            @Override
            public void run() {
                try {
                    tcpClientListener.onConnectionFailed(TcpClient.this, finalException);
                } catch (RuntimeException re) {
                    // RuntimeException from client handling notification -- nothing we can do at this stage
                }
            }
        };

        if (eventsExecutor == null) {
            onConnectionFailed.run();
        } else {
            eventsExecutor.execute(onConnectionFailed);
        }
    }
}
