package org.rouplex;

import org.rouplex.platform.RequestWithSyncReplyHandler;
import org.rouplex.platform.tcp.TcpServerBuilder;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ServerTest {
    public static void main(String[] args) throws Exception {
        TcpServerBuilder tcpServerBuilder = new TcpServerBuilder();
        tcpServerBuilder
                .withLocalAddress(new InetSocketAddress("localhost", 9999))
                .withRequestHandler(new RequestWithSyncReplyHandler<byte[], ByteBuffer>() {
                    @Override
                    public ByteBuffer handleRequest(byte[] request) {
                        return ByteBuffer.wrap(request); // echo
                    }
                })
                .build(true);

        Thread.sleep(10000000);
    }
}
