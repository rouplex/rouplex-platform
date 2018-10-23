package org.rouplex;

import org.junit.Assert;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;
import org.rouplex.platform.tcp.TcpServer;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class EchoServer {
    final Counts counts = new Counts("server");

    EchoServer(
            TcpReactor tcpReactor,
            boolean useExecutor,
            int readBufferSize,
            int writeBufferSize,
            final boolean onlyAsyncRW,
            final int callbackOffenderCount,
            int backlog
    ) throws Exception {
        final AtomicInteger sessionId = new AtomicInteger();
        TcpServer.Builder tcpServerBuilder = new TcpServer.Builder(tcpReactor) {{
            withOnlyAsyncReadWrite(onlyAsyncRW);
        }}
                .withReadBufferSize(readBufferSize)
                .withWriteBufferSize(writeBufferSize)
                .withLocalAddress("localhost", 7777)
                .withBacklog(backlog)
                .withTcpClientListener(new TcpClientListener() {
                    @Override
                    public void onConnected(TcpClient tcpClient) {
                        counts.connectedOk.incrementAndGet();

                        int sid = sessionId.getAndIncrement();
                        tcpClient.setDebugId(sid + "-session");

                        if (sid < callbackOffenderCount) try {
                            report(String.format("%s connected, now delaying", tcpClient.getDebugId()));
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            report("Interrupted");
                        }
                        else {
                            report(String.format("%s connected", tcpClient.getDebugId()));
                        }

                        new Echoer(tcpClient, counts).read(true, true);
                    }

                    @Override
                    public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                        counts.failedConnect.incrementAndGet();
                        report(String.format("%s failed connect", tcpClient.getDebugId()));
                    }

                    @Override
                    public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                        if (optionalReason == null) {
                            counts.disconnectedOk.incrementAndGet();
                        } else {
                            counts.disconnectedKo.incrementAndGet();
                        }

                        report(String.format("%s disconnected (%s)", tcpClient.getDebugId(), optionalReason == null
                                ? "ok" : optionalReason.getClass().getSimpleName() + ": " + optionalReason.getMessage()));
                    }
                });

        if (!useExecutor) {
            tcpServerBuilder.withEventsExecutor(null);
        }

        tcpServerBuilder.build().bind();
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }
}