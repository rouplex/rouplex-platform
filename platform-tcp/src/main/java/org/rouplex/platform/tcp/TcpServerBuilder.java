package org.rouplex.platform.tcp;

import org.rouplex.platform.RequestHandler;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpServerBuilder {
    TcpServer tcpServer = new TcpServer();

    protected void checkCanConfigure() {
        if (tcpServer == null) {
            throw new IllegalStateException("tcpServer is already built and cannot change anymore");
        }
    }

    protected void checkCanBuild() {
        if (tcpServer.localAddress == null) {
            throw new IllegalStateException(
                    "Please define the [localAddress] parameter in order to build the tcpServer");
        }
    }

    public TcpServerBuilder withLocalAddress(InetSocketAddress localAddress) {
        checkCanConfigure();

        tcpServer.localAddress = localAddress;
        return this;
    }

    public TcpServerBuilder withSecure(boolean secure, SSLContext sslContext) throws Exception {
        checkCanConfigure();

        tcpServer.sslContext = secure ? sslContext != null ? sslContext :  SSLContext.getDefault() : null;
        return this;
    }

    public TcpServerBuilder withRequestHandler(RequestHandler<byte[], ByteBuffer> requestHandler) throws Exception {
        checkCanConfigure();

        tcpServer.requestHandler = requestHandler;
        return this;
    }

    public TcpServer build(boolean start) throws Exception {
        checkCanBuild();

        TcpServer tcpServer = this.tcpServer;
        this.tcpServer = null;

        if (start) {
            tcpServer.start();
        }

        return tcpServer;
    }
}

