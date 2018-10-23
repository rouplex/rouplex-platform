package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLServerSocketChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * A class representing a TCP server which provides the mechanism to accept new connections from remote endpoints and
 * create {@link TcpClient}s by wrapping their {@link SocketChannel}s.
 *
 * Instances of this class are obtained via new {@link TcpServer.Builder}
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpServer extends TcpEndPoint {
    public static class Builder extends TcpServerBuilder<TcpServer, Builder> {
        public Builder(TcpReactor tcpReactor) {
            super(tcpReactor);
        }

        /**
         * Build the server and bind it to the local address before returning.
         *
         * @return
         *          The built and bound server
         * @throws Exception
         *          If any problems arise during the server creation and binding
         */
        @Override
        public TcpServer build() throws Exception {
            return buildTcpServer();
        }
    }

    /**
     * A TcpServer builder. The builder can only build one client and once done, any future calls to alter the
     * builder or try to rebuild will fail with {@link IllegalStateException}.
     *
     * Not thread safe.
     */
    protected abstract static class TcpServerBuilder<T, B extends TcpServerBuilder> extends TcpEndPointBuilder<T, B> {
        protected int backlog;
        protected TcpClientListener tcpClientListener;
        protected TcpServerListener tcpServerListener;

        protected TcpServerBuilder(TcpReactor tcpReactor) {
            super(tcpReactor);
        }

        /**
         * An optional {@link ServerSocketChannel}. May be already bound, in which case the localAddress, and the
         * eventual {@link SSLContext} are ignored.
         *
         * @param serverSocketChannel
         *          A server socket channel, bound or not
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withServerSocketChannel(ServerSocketChannel serverSocketChannel) {
            checkNotBuilt();
            if (sslContext != null) {
                throw new IllegalStateException("SslContext is already set and cannot coexist with ServerSocketChannel");
            }

            this.selectableChannel = serverSocketChannel;
            return builder;
        }

        /**
         * The server backlog, indicating the maximum number of connections pending accept.
         *
         * @param backlog
         *          the max number of pending connections
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withBacklog(int backlog) {
            checkNotBuilt();

            this.backlog = backlog;
            return builder;
        }

        /**
         * Set the server lifecycle event listener, providing events related to binding or unbinding a server.
         *
         * @param tcpServerListener
         *          The event listener
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withTcpServerListener(TcpServerListener tcpServerListener) {
            checkNotBuilt();

            this.tcpServerListener = tcpServerListener;
            return builder;
        }

        /**
         * Set the client lifecycle event listener, providing events related accepting new {@link TcpClient}s
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
            if (selectableChannel != null && ((ServerSocketChannel) selectableChannel).socket().isBound()) {
                throw new IllegalStateException("ServerSocketChannel is already bound and LocalAddress cannot be set anymore");
            }
        }

        @Override
        protected void checkCanBuild() {
            super.checkCanBuild();

            if (localAddress != null) {
                return;
            }

            if (selectableChannel == null) {
                throw new IllegalStateException("Missing value for localAddress and serverSocketChannel");
            }

            if (!((ServerSocketChannel) selectableChannel).socket().isBound()) {
                throw new IllegalStateException("Missing value for localAddress and serverSocketChannel is not bound");
            }
        }

        @Override
        protected void prepareBuild() throws Exception {
            super.prepareBuild();

            if (selectableChannel == null) {
                selectableChannel = sslContext == null
                    ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
            }
        }

        protected TcpServer buildTcpServer() throws Exception {
            prepareBuild();
            return new TcpServer(this);
        }
    }

    protected final TcpServerBuilder builder;
    protected final TcpServerListener tcpServerListener;
    protected final TcpClientListener tcpClientListener;

    protected TcpServer(TcpServerBuilder builder) {
        super(builder.selectableChannel, builder.tcpSelector, builder);

        this.builder = builder;
        this.attachment = builder.getAttachment();
        tcpClientListener = builder.tcpClientListener;
        tcpServerListener = builder.tcpServerListener;
    }

    /**
     * Bind the tcpServer to the local address and start listening for client connections. Any socket level
     * configurations can be done before invoking this method, by first getting a reference to the internal
     * {@link ServerSocket}. In particular, set the default size of the receive buffer, setting which will be inherited
     * by all {@link TcpClient}s accepted via this server (per JDK specs, such setting must be set prior to binding to
     * have effect for buffers over 64kb).
     */
    public void bind() throws Exception {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectableChannel;

        synchronized (lock) {
            if (open) {
                throw new IOException("Server is already bound and listening");
            }

            if (closed) {
                throw new IOException("Server is already closed");
            }

            if (!serverSocketChannel.socket().isBound()) {
                // jdk1.7+ serverSocketChannel.bind(localAddress, backlog);
                serverSocketChannel.socket().bind(builder.localAddress, builder.backlog);
                selectableChannel.configureBlocking(false);
            }
        }

        tcpSelector.asyncRegisterTcpEndPoint(this);
        waitForOpen(Long.MAX_VALUE); // todo revisit/shorten this
    }

    /**
     * Get the {@link ServerSocket} instance used internally by this server. The socket obtained, should only be used
     * for configuration such as socket options, buffer sizes etc. (and not for transmitting data or listening for
     * connections) various socket options. In particular, for values over 64kb, the receiveBufferSize must be set
     * before binding.
     *
     * @return
     *          The underlying ServerSocket used by this server. To be used only for socket configuration.
     */
    public ServerSocket getServerSocket() {
        return ((ServerSocketChannel) selectableChannel).socket();
    }

    void syncHandleRegistration() {
        try {
            selectableChannel.register(tcpSelector.selector, SelectionKey.OP_ACCEPT, this);
        } catch (Exception e) {
            handleException(AutoCloseCondition.ON_CHANNEL_EXCEPTION, e, true);
        }

        syncHandleOpen();

        if (tcpServerListener != null) {
            if (eventsExecutor != null) {
                eventsExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            tcpServerListener.onBound(TcpServer.this);
                        } catch (RuntimeException re) {
                            handleException(AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, false);
                        }
                    }
                });
            } else {
                try {
                    tcpServerListener.onBound(this);
                } catch (RuntimeException re) {
                    handleException(AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, true);
                }
            }
        }
    }

    @Override
    void syncHandleUnregistration(@Nullable final Exception optionalReason) {
        if (tcpServerListener != null) {
            if (eventsExecutor != null) {
                eventsExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            tcpServerListener.onUnbound(TcpServer.this, optionalReason);
                        } catch (RuntimeException re) {
                            handleException(AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, false);
                        }
                    }
                });
            } else {
                try {
                    tcpServerListener.onUnbound(this, optionalReason);
                } catch (RuntimeException re) {
                    handleException(AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, true);
                }
            }
        }
    }
}
