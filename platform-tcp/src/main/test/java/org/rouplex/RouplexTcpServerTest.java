package org.rouplex;

import org.rouplex.platform.rr.*;
import org.rouplex.platform.tcp.RouplexTcpServer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpServerTest {
    public static void main(String[] args) throws Exception {
        RouplexTcpServer rouplexTcpServer1 = new RouplexTcpServer()
                .withLocalAddress("localhost", 9991)
                .withServiceProvider(new SyncReplyService<byte[], ByteBuffer>() {
                    @Override
                    public ByteBuffer serviceRequest(byte[] request) {
                        return ByteBuffer.wrap(request); // echo
                    }
                })
                .start();

        RouplexTcpServer rouplexTcpServer2 = new RouplexTcpServer()
                .withLocalAddress("localhost", 9992)
                .withServiceProvider(new AsyncReplyService<byte[], ByteBuffer>() {
                    @Override
                    public void serviceRequest(RequestWithAsyncReply<byte[], ByteBuffer> requestWithAsyncReply) {
                        requestWithAsyncReply.setReply(ByteBuffer.wrap(requestWithAsyncReply.request));
                    }
                })
                .start();

        RouplexTcpServer rouplexTcpServer3 = new RouplexTcpServer()
                .withLocalAddress("localhost", 9993)
                .withServiceProvider(new AsyncRepliesService<byte[], ByteBuffer>() {
                    @Override
                    public void serviceRequest(RequestWithAsyncReplies<byte[], ByteBuffer> request) {
                        request.addReply(ByteBuffer.wrap(request.request));
                        request.addReply(ByteBuffer.wrap(request.request));
                        request.addReply(null);
                    }
                })
                .start();
        Thread.sleep(100000);

        final InetSocketAddress serverAddress = rouplexTcpServer3.getLocalAddress();
        String hn = serverAddress.getHostName();

        int clientCount = 1;
        ExecutorService executorService = Executors.newFixedThreadPool(clientCount);

        final byte[] readBuffer = new byte[1000];

        for (int i = 0; i < clientCount; i++) {
            final int c = i;
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket socket = new Socket(serverAddress.getHostName(), serverAddress.getPort());

                        for (char i = 'a'; i < 'z'; i++) {
                           // Thread.sleep(1);
                            socket.getOutputStream().write(("a" + i).getBytes());
                            socket.getInputStream().read(readBuffer);
                        }

                        socket.close();

                        System.out.println("Client: " + c + " happy");
                    } catch (Exception e) {
                       // System.out.println("Client: " + c + " unhappy " + e.getMessage());
                    }
                }
            });
        }

   //     rouplexTcpServer.close();
Thread.sleep(100000);
        executorService.shutdownNow();
        executorService.awaitTermination(1, TimeUnit.SECONDS);

    }
}
