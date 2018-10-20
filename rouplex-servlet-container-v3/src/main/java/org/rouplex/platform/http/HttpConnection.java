package org.rouplex.platform.http;

import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpReadChannel;
import org.rouplex.platform.tcp.TcpWriteChannel;

import javax.servlet.Servlet;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class HttpConnection implements Closeable {
    private final TcpClient tcpClient;
    private final TcpReadChannel tcpReadChannel;
    private final TcpWriteChannel tcpWriteChannel;
    private final HttpServer httpServer;
    private ByteBuffer readBuffer = ByteBuffer.allocate(0x10000);
    private Servlet currentServlet;

    HttpConnection(TcpClient tcpClient, HttpServer httpServer) {
        this.tcpClient = tcpClient;
        this.tcpReadChannel = tcpClient.getReadChannel();
        this.tcpWriteChannel = tcpClient.getWriteChannel();
        this.httpServer = httpServer;
    }

    @Override
    public void close() throws IOException {
        tcpClient.close();
    }
//
//    Runnable reader = new Runnable() {
//        @Override
//        public void run() {
//            try {
//                while (currentServlet == null) {
//                    switch (tcpReadChannel.read(buffer)) {
//                        case -1:
//                            buffer = null;
//                    }
//                }
//            } catch (IOException re) {
//                // report
//                try {
//                    close();
//                } catch (IOException ce) {
//                    // report
//                }
//            }
//        }
//    }

}
