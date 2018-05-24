package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLServerSocketChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;

/**
 * A class representing a TCP server and which provides the mechanism to accept new connections from remote endpoints
 * and create {@link TcpClient}s by wrapping them.
 *
 * Instances of this class are obtained via the builder obtainable in its turn via a call to
 * {@link TcpReactor#newTcpServerBuilder()}.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpServer extends TcpEndPoint {

    /**
     * A TcpServer builder. The builder can only build one client and once done, any future calls to alter the
     * builder or try to rebuild will fail with {@link IllegalStateException}.
     */
    public static class Builder extends TcpEndPoint.Builder<TcpServer, Builder> {
        protected int backlog;
        protected TcpClientLifecycleListener tcpClientLifecycleListener;
        protected TcpServerLifecycleListener tcpServerLifecycleListener;

        Builder(TcpReactor tcpReactor) {
            super(tcpReactor);
        }

        protected void checkCanBuild() {
            if (localAddress == null) {
                throw new IllegalStateException("Missing value for localAddress"); // maybe remove this constraint
            }
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
        synchronized public Builder withServerSocketChannel(ServerSocketChannel serverSocketChannel) {
            checkNotBuilt();

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
        synchronized public Builder withBacklog(int backlog) {
            checkNotBuilt();

            this.backlog = backlog;
            return builder;
        }

        /**
         * Weather the server should accept only secure clients or not. If secure, the sslContext provides the means to
         * access the key and trust stores; if sslContext is null then the default {@link SSLContext}, containing JRE's
         * defaults, and obtainable internally via {@link SSLContext#getDefault()} will be used. If not secure, the
         * {@link SSLContext#getDefault()} should be null and will be ignored.
         *
         * @param secure
         *          True if the server should accept only secured connections from remote endpoints
         * @param sslContext
         *          The sslContext to use, or null if system's defaults should be used
         * @return
         *          The reference to this builder for chaining calls
         */
        synchronized public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) {
            checkNotBuilt();

            try {
                this.sslContext = secure ? sslContext != null ? sslContext : SSLContext.getDefault() : null;
            } catch (Exception e) {
                throw new RuntimeException("Could not create SSLContext.", e);
            }

            return builder;
        }

        /**
         * Set the server lifecycle event listener, providing events related to binding or unbinding a server.
         *
         * @param tcpServerLifecycleListener
         *          The event listener
         * @return
         *          The reference to this builder for chaining calls
         */
        synchronized public Builder withTcpServerLifecycleListener(TcpServerLifecycleListener tcpServerLifecycleListener) {
            checkNotBuilt();

            this.tcpServerLifecycleListener = tcpServerLifecycleListener;
            return builder;
        }

        /**
         * Set the client lifecycle event listener, providing events related accepting new {@link TcpClient}s
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
         * Build the server and bind it to the local address before returning.
         *
         * @return
         *          The built and bound server
         * @throws IOException
         *          If any problems arise during the server creation and binding
         */
        @Override
        synchronized public TcpServer build() throws IOException {
            checkNotBuilt();
            checkCanBuild();
            builder = null;

            if (selectableChannel == null) {
                selectableChannel = sslContext == null
                        ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
            }

            return new TcpServer(this);
        }
    }

    private Builder builder;
    final TcpServerLifecycleListener tcpServerLifecycleListener;
    final TcpClientLifecycleListener tcpClientLifecycleListener;

    TcpServer(Builder builder) throws IOException {
        super(builder.selectableChannel, builder.tcpSelector, builder.attachment);

        this.builder = builder;
        tcpClientLifecycleListener = builder.tcpClientLifecycleListener;
        tcpServerLifecycleListener = builder.tcpServerLifecycleListener;
    }

    /**
     * Bind the tcpServer to the local address and start listening for client connections. Any socket level
     * configurations can be done before invoking this method, by first getting a reference to the internal
     * {@link ServerSocket}. In particular, set the default size of the receive buffer, setting which will be inherited
     * by all {@link TcpClient}s accepted via this server (per JDK specs, such setting must be set prior to binding to
     * have effect for buffers over 64kb).
     */
    public void bind() throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectableChannel;

        synchronized (lock) {
            if (builder == null) {
                throw new IOException("Server is already " + (open ? "bound and listening" : closed ? "closed" : "binding"));
            }

            if (!serverSocketChannel.socket().isBound()) {
                // jdk1.7+ serverSocketChannel.bind(localAddress, backlog);
                serverSocketChannel.socket().bind(builder.localAddress, builder.backlog);
                selectableChannel.configureBlocking(false);
            }

            builder = null;
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

    void handleRegistration() throws Exception {
        selectableChannel.register(tcpSelector.selector, SelectionKey.OP_ACCEPT, this);
        handleOpen();

        if (tcpServerLifecycleListener != null) {
            tcpServerLifecycleListener.onBound(this);
        }
    }

    @Override
    void handleUnregistration(@Nullable Exception optionalReason) {
        if (tcpServerLifecycleListener != null) {
            tcpServerLifecycleListener.onUnbound(this, optionalReason);
        }
    }
}
