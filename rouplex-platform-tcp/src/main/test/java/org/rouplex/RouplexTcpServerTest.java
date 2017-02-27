package org.rouplex;

import org.rouplex.platform.rr.NotificationListener;
import org.rouplex.platform.rr.ReceiveChannel;
import org.rouplex.platform.rr.SendChannel;
import org.rouplex.platform.rr.Throttle;
import org.rouplex.platform.tcp.RouplexTcpClient;
import org.rouplex.platform.tcp.RouplexTcpServer;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpServerTest {
    public static void main(String[] args) throws Exception {

        RouplexTcpServer rouplexTcpServer1 = RouplexTcpServer.newBuilder()
                .withLocalAddress("localhost", 9991)
                .build();

        rouplexTcpServer1.getRouplexTcpBinder().setTcpClientAddedListener(new NotificationListener<RouplexTcpClient>() {

            @Override
            public void onEvent(final RouplexTcpClient addedRouplexTcpClient) {
                final AtomicReference<ByteBuffer> echoing = new AtomicReference<ByteBuffer>();
                final AtomicReference<Throttle> receiveThrottle = new AtomicReference<Throttle>();

                final SendChannel<ByteBuffer> sendChannel = addedRouplexTcpClient.hookSendChannel(new Throttle() {
                    @Override
                    public void resume() {
                        receiveThrottle.get().resume();
                    }
                });

                receiveThrottle.set(addedRouplexTcpClient.hookReceiveChannel(new ReceiveChannel<byte[]>() {
                    @Override
                    public boolean receive(byte[] payload) {
                        echoing.set(ByteBuffer.wrap(payload));
                        return sendChannel.send(echoing.get()); // returning false pauses future receives
                    }
                }));
            }
        });

        System.out.println("P: " + ((InetSocketAddress) rouplexTcpServer1.getLocalAddress()).getPort());




        Thread.sleep(100000);

        final InetSocketAddress serverAddress = null;//(InetSocketAddress) rouplexTcpServer3.getLocalAddress();
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
