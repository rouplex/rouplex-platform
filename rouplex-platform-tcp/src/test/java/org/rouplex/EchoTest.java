package org.rouplex;

import org.rouplex.platform.tcp.TcpReactor;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientLifecycleListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple manual verification with a number of clients sending just a line of text
 * to a server, which echoes it back in return.
 *
 * Simple verify that the sequence of events is of form (session number may be different from 0):

 TcpClient:0 connected
 TcpClient:0 sending [Hello from TcpClient:0]
 TcpClient:0 sent [Hello from TcpClient:0]
 TcpClient:0 shutting down
 TcpClient:0 shutdown
 TcpClient:0 received [Hello from TcpClient:0]
 TcpClient:0 received eos
 TcpClient:0 closing
 TcpClient:0 closed
 TcpClient:0 disconnected

 TcpSession:0 connected
 TcpSession:0 received [Hello from TcpClient:0]
 TcpSession:0 sending [Hello from TcpClient:0]
 TcpSession:0 sent [Hello from TcpClient:0]
 TcpSession:0 received eos
 TcpSession:0 closing
 TcpSession:0 closed
 TcpSession:0 disconnected

 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class EchoTest {
    final TcpClient tcpClient;
    final boolean server;

    EchoTest(TcpClient tcpClient, boolean server) {
        this.tcpClient = tcpClient;
        this.server = server;
    }

    public static void main(String[] args) throws Exception {
        TcpReactor tcpReactor = new TcpReactor();
        AtomicInteger sessionId = new AtomicInteger();

        tcpReactor.newTcpServerBuilder()
            .withLocalAddress("localhost", 7777)
            .withTcpClientLifecycleListener(new TcpClientLifecycleListener() {
                @Override
                public void onConnected(TcpClient tcpClient) {
                    tcpClient.setDebugId("TcpSession:" + sessionId.getAndIncrement());
                    System.out.println(String.format("%s connected", tcpClient.getDebugId()));
                    new EchoTest(tcpClient, true).read(true, true);
                }

                @Override
                public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                    System.out.println(String.format("%s failed connect", tcpClient.getDebugId()));
                }

                @Override
                public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                    System.out.println(String.format("%s disconnected", tcpClient.getDebugId()));
                }
            })
            .build().bind();

        for (int i = 0; i < 10; i++) {
            TcpClient tcpClient = tcpReactor.newTcpClientBuilder()
                .withRemoteAddress("localhost", 7777)
                .withTcpClientLifecycleListener(new TcpClientLifecycleListener() {
                    @Override
                    public void onConnected(TcpClient tcpClient) {
                        System.out.println(String.format("%s connected", tcpClient.getDebugId()));
                        ByteBuffer bb = ByteBuffer.wrap(("Hello from " + tcpClient.getDebugId()).getBytes());
                        EchoTest echoTest = new EchoTest(tcpClient, false);
                        echoTest.write(bb, true, false);
                        echoTest.read(false, true);
                    }

                    @Override
                    public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                        System.out.println(String.format("%s failed connect", tcpClient.getDebugId()));
                    }

                    @Override
                    public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                        System.out.println(String.format("%s disconnected", tcpClient.getDebugId()));
                    }
                }).build();

            tcpClient.setDebugId("TcpClient:" + i);
            tcpClient.connect();
        }

        Thread.sleep(2000);
    }

    void read(boolean thenWrite, boolean thenClose) {
        ByteBuffer bb = ByteBuffer.allocate(1000);

        try {
            int read = tcpClient.getReadChannel().read(bb);
            switch (read) {
                case -1:
                    System.out.println(String.format("%s received eos", tcpClient.getDebugId()));
                    write(null, false, thenClose);
                    return;
                case 0:
                    tcpClient.getReadChannel().addChannelReadyCallback(new Runnable() {
                        @Override
                        public void run() {
                            read(thenWrite, thenClose);
                        }
                    });
                    break;
                default:
                    bb.flip();
                    String payload = new String(bb.array(), 0, bb.limit());
                    System.out.println(String.format("%s received [%s]", tcpClient.getDebugId(), payload));
                    if (thenWrite) {
                        write(bb, false, false);
                    }

                    read(thenWrite, thenClose);
            }
        } catch (IOException ioe) {
            System.out.println(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }

    void write(ByteBuffer bb, boolean thenShutdown, boolean thenClose) {
        try {
            if (bb != null && bb.hasRemaining()) {
                String payload = new String(bb.array(), 0, bb.limit());
                System.out.println(String.format("%s sending [%s]", tcpClient.getDebugId(), payload));
                tcpClient.getWriteChannel().write(bb);
                System.out.println(String.format("%s sent [%s]", tcpClient.getDebugId(), payload));

                if (bb.hasRemaining()) {
                    tcpClient.getWriteChannel().addChannelReadyCallback(new Runnable() {
                        @Override
                        public void run() {
                            write(bb, thenShutdown, thenClose);
                        }
                    });

                    return;
                }
            }

            if (thenShutdown) {
                System.out.println(String.format("%s shutting down", tcpClient.getDebugId()));
                tcpClient.getWriteChannel().shutdown();
                System.out.println(String.format("%s shutdown", tcpClient.getDebugId()));
            }

            if (thenClose) {
                System.out.println(String.format("%s closing", tcpClient.getDebugId()));
                tcpClient.close();
                System.out.println(String.format("%s closed", tcpClient.getDebugId()));
            }
        } catch (IOException ioe) {
            System.out.println(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }
}