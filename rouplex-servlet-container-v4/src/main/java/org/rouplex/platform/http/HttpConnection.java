package org.rouplex.platform.http;

import org.rouplex.platform.io.ReactiveReadChannel;
import org.rouplex.platform.io.ReactiveWriteChannel;
import org.rouplex.platform.tcp.TcpClient;

import javax.servlet.Servlet;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class HttpConnection implements Closeable {
    private final TcpClient tcpClient;
    private final ReactiveReadChannel readChannel;
    private final ReactiveWriteChannel writeChannel;
    private final HttpServer httpServer;
    private ByteBuffer readBuffer = ByteBuffer.allocate(0x10000);
    private Servlet currentServlet;

    HttpConnection(TcpClient tcpClient, HttpServer httpServer) {
        this.tcpClient = tcpClient;
        this.readChannel = tcpClient.getReadChannel();
        this.writeChannel = tcpClient.getWriteChannel();
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
//                    switch (readChannel.read(buffer)) {
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
