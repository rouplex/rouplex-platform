package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLServerSocketChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

/**
 * A class representing a TCP server and which provides the mechanism to accept new connections from remote endpoints
 * and create {@link RouplexTcpClient}s by wrapping them.
 *
 * Instances of this class are obtained via the builder obtainable in its turn via a call to
 * {@link RouplexTcpBinder#newRouplexTcpServerBuilder()}.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpServer extends RouplexTcpEndPoint {

    /**
     * A RouplexTcpServer builder. The builder can only build one client and once done, any future calls to alter the
     * builder or try to rebuild will fail with {@link IllegalStateException}. The builder is not thread safe.
     */
    public static class Builder extends RouplexTcpEndPoint.Builder<RouplexTcpServer, Builder> {
        protected int backlog;
        protected RouplexTcpServerListener rouplexTcpServerListener;
        protected RouplexTcpClientListener rouplexTcpClientListener;

        Builder(RouplexTcpBinder rouplexTcpBinder) {
            super(rouplexTcpBinder);
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
         *          a server socket channel, bound or not
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withServerSocketChannel(ServerSocketChannel serverSocketChannel) {
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
         *          the reference to this builder for chaining calls
         */
        public Builder withBacklog(int backlog) {
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
         *          true if the server should accept only secured connections from remote endpoints
         * @param sslContext
         *          the sslContext to use, or null if system's defaults should be used
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withSecure(boolean secure, @Nullable SSLContext sslContext) {
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
         * @param rouplexTcpServerListener
         *          the event listener
         * @return
         *          the reference to this builder for chaining calls
         */
        public Builder withRouplexTcpServerListener(RouplexTcpServerListener rouplexTcpServerListener) {
            checkNotBuilt();

            this.rouplexTcpServerListener = rouplexTcpServerListener;
            return builder;
        }

        /**
         * Set the server lifecycle event listener, providing events related to binding or unbinding a server.
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
         * Build the server and bind it to the local address before returning.
         *
         * @return
         *          the built and bound server
         * @throws IOException
         *          if any problems arise during the server creation and binding
         */
        @Override
        public RouplexTcpServer buildAsync() throws IOException {
            checkNotBuilt();
            checkCanBuild();

            if (selectableChannel == null) {
                selectableChannel = sslContext == null
                        ? ServerSocketChannel.open() : SSLServerSocketChannel.open(sslContext);
            }

            return new RouplexTcpServer(this);
        }
    }

    final int sendBufferSize;
    final int receiveBufferSize;
    final RouplexTcpServerListener rouplexTcpServerListener;

    RouplexTcpServer(Builder builder) throws IOException {
        super(builder);

        sendBufferSize = builder.sendBufferSize;
        receiveBufferSize = builder.receiveBufferSize;
        rouplexTcpServerListener = builder.rouplexTcpServerListener;

        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectableChannel;

        serverSocketChannel.configureBlocking(false);
        if (!serverSocketChannel.socket().isBound()) {
            if (builder.receiveBufferSize != 0) {
                // serverSocket doesn't have sendBufferSize, and receiveBufferSize must be set beforehand for values
                // over 64kb (we set it for all > 0), then set again on the individual accepted sockets
                serverSocketChannel.socket().setReceiveBufferSize(builder.receiveBufferSize);
            }

            // jdk1.7+ serverSocketChannel.bind(builder.localAddress, ((Builder) builder).backlog);
            serverSocketChannel.socket().bind(builder.localAddress, builder.backlog);
        }

        rouplexTcpSelector.asyncRegisterTcpEndPoint(this);
    }

    /**
     * Called from {@link RouplexTcpSelector} in the context of a background thread, when the server channel is
     * registered with the internal {@link Selector}, to update the server's state and fire the onBound notification.
     */
    void handleBound() {
        handleOpen();

        if (rouplexTcpServerListener != null) {
            rouplexTcpServerListener.onBound(this);
        }
    }

    /**
     * Called from {@link RouplexTcpSelector} in the context of a background thread, when the server channel is
     * unregistered with the internal {@link Selector}, to fire the onUnBound notification.
     */
    void handleUnBound() {
        if (rouplexTcpServerListener != null) {
            rouplexTcpServerListener.onUnBound(this);
        }
    }
}
