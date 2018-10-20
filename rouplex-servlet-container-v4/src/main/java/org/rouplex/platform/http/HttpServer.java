package org.rouplex.platform.http;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.commons.builders.SingleInstanceBuilder;
import org.rouplex.commons.utils.ValidationUtils;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;
import org.rouplex.platform.tcp.TcpServer;

import javax.net.ssl.SSLContext;
import javax.servlet.Servlet;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class HttpServer {
    public static class Builder extends HttpServerBuilder<HttpServer, Builder> {
        protected Builder(TcpReactor tcpReactor) {
            super(tcpReactor);
        }

        public HttpServer build() throws Exception {
            return buildHttpServer();
        }
    }

    protected abstract static class HttpServerBuilder<T, B extends SingleInstanceBuilder> extends SingleInstanceBuilder<T, B> {
        final TcpServer.Builder tcpServerBuilder;
        int requestHeadersLimit = 0x10000; // 64Kb by default
        long requestLimit = -1; // unlimited by default

        protected HttpServerBuilder(TcpReactor tcpReactor) {
            tcpServerBuilder = new TcpServer.Builder(tcpReactor);
        }

        public B withLocalAddress(SocketAddress localAddress) {
            checkNotBuilt();
            tcpServerBuilder.withLocalAddress(localAddress);
            return builder;
        }

        public B withLocalAddress(@Nullable String hostname, int port) {
            checkNotBuilt();
            tcpServerBuilder.withLocalAddress(hostname, port);
            return builder;
        }

        /**
         * Enable SSL/TLS and use sslContext for all related configurations, such as access to the key and trust
         * stores, ciphers and protocols to use. If sslContext is null then a plain connection will be used.
         *
         * If a socketChannel is already set, then the connection's security is governed by its settings, and a
         * call to this method will fail with {@link IllegalStateException}.
         *
         * @param sslContext
         *          The sslContext to use for SSL/TLS or null if plain connection is preferred.
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withSecure(@Nullable SSLContext sslContext) {
            checkNotBuilt();
            tcpServerBuilder.withSecure(sslContext);
            return builder;
        }

        /**
         * The server backlog, indicating the maximum number of connections pending accept.
         *
         * @param backlog the max number of pending connections
         * @return The reference to this builder for chaining calls
         */
        public B withBacklog(int backlog) {
            checkNotBuilt();
            tcpServerBuilder.withBacklog(backlog);
            return builder;
        }

        /**
         * The maximum size allowed for headers of incoming http requests. By default it is 64Kb.
         * To disable the setting, set to -1, though that is not advisable.
         *
         * @param requestHeadersLimit
         *          the max number of bytes composing the headers portion of an HTTP request.
         * @return The reference to this builder for chaining calls
         */
        public B withRequestHeadersLimit(int requestHeadersLimit) {
            checkNotBuilt();
            this.requestHeadersLimit = requestHeadersLimit;
            return builder;
        }

        /**
         * The maximum size allowed for the of incoming http requests.
         * By default it is -1, which means it is disabled (unlimited).
         *
         * @param requestLimit
         *          the max number of bytes composing the HTTP request.
         * @return The reference to this builder for chaining calls
         */
        public B withRequestLimit(long requestLimit) {
            checkNotBuilt();
            this.requestLimit = ValidationUtils.checkedGreaterThanMinusOne(requestLimit, "requestLimit");
            return builder;
        }

        @Override
        protected void checkCanBuild() {
        }

        protected HttpServer buildHttpServer() throws Exception {
            prepareBuild();
            return new HttpServer(this);
        }
    }

    private final Object lock = new Object();
    private final Map<String, Servlet> servlets = new HashMap<String, Servlet>();

    public HttpServer(HttpServerBuilder builder) throws IOException {
        builder.tcpServerBuilder.withTcpClientListener(new TcpClientListener() {
            @Override
            public void onConnected(TcpClient tcpClient) {

            }

            @Override
            public void onConnectionFailed(TcpClient client, Exception reason) {

            }

            @Override
            public void onDisconnected(TcpClient client, Exception optionalReason) {

            }
        });
    }

    public void addServlet(Servlet servlet) {
        synchronized (lock) {
            servlets.put(servlet.getServletConfig().getServletContext().getContextPath(), servlet);
        }
    }
}
