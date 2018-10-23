package org.rouplex;

import org.junit.Assert;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;
import org.rouplex.platform.tcp.TcpServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple manual verification with a number of clients sending just a line of text
 * to a server, which echoes it back in return.
 * <p>
 * Simple verify that the sequence of events is of form (session number may be different from 0 and entries reordered):
 * <p>
         0-session connected
         0-client connected
         0-client sending [Hello from 0-client]
         0-client sent [Hello from 0-client]
         0-session received [Hello from 0-client]
         0-client shutting down
         0-session sending [Hello from 0-client]
         0-session sent [Hello from 0-client]
         0-client shutdown
         0-session received eos
         0-session closing
         0-client received [Hello from 0-client]
         0-session closed
         0-client received eos
         0-client closing
         0-client closed
         0-client disconnected
         0-session disconnected
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class BaseEchoTest {
    private final static AtomicInteger clientsConnectedOk = new AtomicInteger();
    private final static AtomicInteger clientsFailedConnect = new AtomicInteger();
    private final static AtomicInteger clientsFailedWrite = new AtomicInteger();
    private final static AtomicInteger clientsFailedRead = new AtomicInteger();
    private final static AtomicInteger clientsDisconnectedOk = new AtomicInteger();
    private final static AtomicInteger clientsDisconnectedKo = new AtomicInteger();
    private final static AtomicInteger clientsReceivedEos = new AtomicInteger();

    private final static AtomicInteger sessionsConnectedOk = new AtomicInteger();
    private final static AtomicInteger sessionsConnectedKo = new AtomicInteger();
    private final static AtomicInteger sessionsFailedWrite = new AtomicInteger();
    private final static AtomicInteger sessionsFailedRead = new AtomicInteger();
    private final static AtomicInteger sessionsDisconnectedOk = new AtomicInteger();
    private final static AtomicInteger sessionsDisconnectedKo = new AtomicInteger();
    private final static AtomicInteger sessionsReceivedEos = new AtomicInteger();

    private final TcpClient tcpClient;

    BaseEchoTest(TcpClient tcpClient) {
        this.tcpClient = tcpClient;
    }

    public static void main(String[] args) throws Exception {
        int clientCount = 100;
        final CountDownLatch clientConnections = new CountDownLatch(2 * clientCount);

        long start = System.currentTimeMillis();
        TcpReactor tcpReactor = new TcpReactor.Builder().withThreadCount(0).build();

        final AtomicInteger sessionId = new AtomicInteger();
        new TcpServer.Builder(tcpReactor)
                .withBacklog(20)
                .withEventsExecutor(null)
                .withReadBufferSize(0)
                .withWriteBufferSize(0)
                .withLocalAddress("localhost", 7777)
                .withTcpClientListener(new TcpClientListener() {
                    @Override
                    public void onConnected(TcpClient tcpClient) {
                        sessionsConnectedOk.incrementAndGet();
                        
                        int sid = sessionId.getAndIncrement();
                        tcpClient.setDebugId(sid + "-session");
                        report(String.format("%s connected", tcpClient.getDebugId()));
                        new BaseEchoTest(tcpClient).read(true, true);
                    }

                    @Override
                    public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                        sessionsConnectedKo.incrementAndGet();
                        report(String.format("%s failed connect", tcpClient.getDebugId()));
                    }

                    @Override
                    public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                        if (optionalReason == null) {
                            sessionsDisconnectedOk.incrementAndGet();
                        } else {
                            sessionsDisconnectedKo.incrementAndGet();
                        }
                        
                        report(String.format("%s disconnected", tcpClient.getDebugId()));
                    }
                })
                .build().bind();

        for (int i = 0; i < clientCount; i++) {
            TcpClient tcpClient = new TcpClient.Builder(tcpReactor)
                    .withEventsExecutor(null)
                    .withRemoteAddress("localhost", 7777)
                    .withReadBufferSize(0)
                    .withWriteBufferSize(0)
                    .withTcpClientListener(new TcpClientListener() {
                        @Override
                        public void onConnected(TcpClient tcpClient) {
                            clientConnections.countDown();
                            clientsConnectedOk.incrementAndGet();

                            report(String.format("%s connected", tcpClient.getDebugId()));
                            ByteBuffer bb = ByteBuffer.wrap(("Hello from " + tcpClient.getDebugId()).getBytes());
                            BaseEchoTest baseEchoTest = new BaseEchoTest(tcpClient);
                            baseEchoTest.write(bb, true, false);
                            baseEchoTest.read(false, true);
                        }

                        @Override
                        public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                            clientConnections.countDown();
                            clientConnections.countDown(); // simulate the connect/disconnect
                            clientsFailedConnect.incrementAndGet();
                            
                            report(String.format("%s failed connect", tcpClient.getDebugId()));
                        }

                        @Override
                        public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                            clientConnections.countDown();
                            if (optionalReason == null) {
                                clientsDisconnectedOk.incrementAndGet();
                            } else {
                                clientsDisconnectedKo.incrementAndGet();
                            }

                            report(String.format("%s disconnected", tcpClient.getDebugId()));
                        }
                    }).build();

            tcpClient.setDebugId(i + "-client");
            tcpClient.connect();
        }

        clientConnections.await(clientCount * 10000000, TimeUnit.MILLISECONDS);
        Thread.sleep(100); // separate printouts
        tcpReactor.close();

        Assert.assertEquals(0, clientConnections.getCount());
        System.out.println(
                "\nExec time millis: " + (System.currentTimeMillis() - start) +
                "\nclientsConnectedOk: " + clientsConnectedOk.get() +
                "\nclientsFailedConnect: " + clientsFailedConnect.get() +
                "\nclientsFailedRead: " + clientsFailedRead.get() +
                "\nclientsFailedWrite: " + clientsFailedWrite.get() +
                "\nclientsDisconnectedOk: " + clientsDisconnectedOk.get() +
                "\nclientsDisconnectedKo: " + clientsDisconnectedKo.get() +
                "\nclientsReceivedEos: " + clientsReceivedEos.get() +
                "\nsessionsConnectedOk: " + sessionsConnectedOk.get() +
                "\nsessionsConnectedKo: " + sessionsConnectedKo.get() +
                "\nsessionsFailedRead: " + clientsFailedRead.get() +
                "\nclientsFailedWrite: " + clientsFailedWrite.get() +
                "\nsessionsDisconnectedOk: " + sessionsDisconnectedOk.get() +
                "\nsessionsDisconnectedKo: " + sessionsDisconnectedKo.get() +
                "\nsessionsReceivedEos: " + sessionsReceivedEos.get()
        );
    }

    void read(final boolean thenWrite, final boolean thenClose) {
        ByteBuffer bb = ByteBuffer.allocate(1000);

        try {
            int read = tcpClient.getReadChannel().read(bb);
            switch (read) {
                case -1:
                    if (tcpClient.getOriginatingTcpServer() == null) {
                        clientsReceivedEos.incrementAndGet();
                    } else {
                        sessionsReceivedEos.incrementAndGet();
                    }

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
            if (tcpClient.getOriginatingTcpServer() == null) {
                clientsFailedRead.incrementAndGet();
            } else {
                sessionsFailedRead.incrementAndGet();
            }

            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }

    void write(final ByteBuffer bb, final boolean thenShutdown, final boolean thenClose) {
        try {
            if (bb != null && bb.hasRemaining()) {
                int position = bb.position();

                report(String.format("%s sending [%s]", tcpClient.getDebugId(),
                        new String(bb.array(), position, bb.limit() - position)));
                
                tcpClient.getWriteChannel().write(bb);

                report(String.format("%s sent [%s]", tcpClient.getDebugId(),
                        new String(bb.array(), position, bb.position() - position)));

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
        } catch (Exception ioe) {
            if (tcpClient.getOriginatingTcpServer() == null) {
                clientsFailedWrite.incrementAndGet();
            } else {
                sessionsFailedWrite.incrementAndGet();
            }

            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }
}