package org.rouplex;

import org.junit.Assert;
import org.rouplex.platform.tcp.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple manual verification with a number of clients sending just a line of text
 * to a server, which echoes it back in return.
 * <p>
 * Simple verify that the sequence of events is of form (session number may be different from 0):
 * <p>
 * TcpClient:0 connected
 * TcpClient:0 sending [Hello from TcpClient:0]
 * TcpClient:0 sent [Hello from TcpClient:0]
 * TcpClient:0 shutting down
 * TcpClient:0 shutdown
 * TcpClient:0 received [Hello from TcpClient:0]
 * TcpClient:0 received eos
 * TcpClient:0 closing
 * TcpClient:0 closed
 * TcpClient:0 disconnected
 * <p>
 * TcpSession:0 connected
 * TcpSession:0 received [Hello from TcpClient:0]
 * TcpSession:0 sending [Hello from TcpClient:0]
 * TcpSession:0 sent [Hello from TcpClient:0]
 * TcpSession:0 received eos
 * TcpSession:0 closing
 * TcpSession:0 closed
 * TcpSession:0 disconnected
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class EchoTestWithMisbehaviors {
    final TcpClient tcpClient;
    final boolean server;

    EchoTestWithMisbehaviors(TcpClient tcpClient, boolean server) {
        this.tcpClient = tcpClient;
        this.server = server;
    }

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        TcpReactor tcpReactor = new TcpReactor.Builder()
                .withThreadCount(1).build();

        final AtomicInteger sessionId = new AtomicInteger();
        new TcpServer.Builder(tcpReactor)
                .withLocalAddress("localhost", 7777)
                .withTcpClientListener(new TcpClientListener() {
                    @Override
                    public void onConnected(TcpClient tcpClient) {
                        int sid = sessionId.getAndIncrement();
                        tcpClient.setDebugId("TcpSession:" + sid);

                        if (sid == 0) try {
                            report(String.format("%s connected, now delaying", tcpClient.getDebugId()));
                            Thread.sleep(10000);
                        } catch (InterruptedException ie) {
                            report("Interrupted");
                        }
                        else {
                            report(String.format("%s connected", tcpClient.getDebugId()));
                        }

                        new EchoTest(tcpClient, true).read(true, true);
                    }

                    @Override
                    public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                        report(String.format("%s failed connect", tcpClient.getDebugId()));
                    }

                    @Override
                    public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                        report(String.format("%s disconnected", tcpClient.getDebugId()));
                    }
                })
                .build().bind();

        int clientCount = 200;
        CountDownLatch countDownLatch = new CountDownLatch(clientCount);

        for (int i = 0; i < clientCount; i++) {
            TcpClient tcpClient = new TcpClient.Builder(tcpReactor)
                    .withEventsExecutor(null)
                    .withRemoteAddress("localhost", 7777)
                    .withTcpClientListener(new TcpClientListener() {
                        @Override
                        public void onConnected(TcpClient tcpClient) {
                            report(String.format("%s connected", tcpClient.getDebugId()));
                            ByteBuffer bb = ByteBuffer.wrap(("Hello from " + tcpClient.getDebugId()).getBytes());
                            EchoTest echoTest = new EchoTest(tcpClient, false);
                            echoTest.write(bb, true, false);
                            echoTest.read(false, true);
                        }

                        @Override
                        public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                            countDownLatch.countDown();
                            report(String.format("%s failed connect", tcpClient.getDebugId()));
                        }

                        @Override
                        public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                            countDownLatch.countDown();
                            report(String.format("%s disconnected", tcpClient.getDebugId()));
                        }
                    }).build();

            tcpClient.setDebugId("TcpClient:" + i);
            tcpClient.connect();
        }

        countDownLatch.await(clientCount * 10, TimeUnit.MILLISECONDS);
        tcpReactor.close();

        // One tcp session was delayed on purpose, and did not block others
        Assert.assertEquals(1, countDownLatch.getCount());
        report("Exec time millis: " + (System.currentTimeMillis() - start));
    }

    void read(final boolean thenWrite, final boolean thenClose) {
        ByteBuffer bb = ByteBuffer.allocate(1000);

        try {
            int read = tcpClient.getReadChannel().read(bb);
            switch (read) {
                case -1:
                    report(String.format("%s received eos", tcpClient.getDebugId()));
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
                    report(String.format("%s received [%s]", tcpClient.getDebugId(), payload));
                    if (thenWrite) {
                        write(bb, false, false);
                    }

                    read(thenWrite, thenClose);
            }
        } catch (IOException ioe) {
            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }

    void write(final ByteBuffer bb, final boolean thenShutdown, final boolean thenClose) {
        try {
            if (bb != null && bb.hasRemaining()) {
                String payload = new String(bb.array(), 0, bb.limit());
                report(String.format("%s sending [%s]", tcpClient.getDebugId(), payload));
                tcpClient.getWriteChannel().write(bb);
                report(String.format("%s sent [%s]", tcpClient.getDebugId(), payload));

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
                report(String.format("%s shutting down", tcpClient.getDebugId()));
                tcpClient.getWriteChannel().shutdown();
                report(String.format("%s shutdown", tcpClient.getDebugId()));
            }

            if (thenClose) {
                report(String.format("%s closing", tcpClient.getDebugId()));
                tcpClient.close();
                report(String.format("%s closed", tcpClient.getDebugId()));
            }
        } catch (IOException ioe) {
            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }

    static void report(String log) {
        System.out.println(String.format("%s %s", System.currentTimeMillis(), log));
    }
}