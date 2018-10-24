package org.rouplex.tcp;

import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;
import org.rouplex.platform.tcp.TcpServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

class EchoServer implements Closeable {
    protected final Counts counts = new Counts("server");
    protected final TcpServer tcpServer;

    protected EchoServer(
            TcpReactor tcpReactor,
            int localPort,
            boolean useExecutor,
            int readBufferSize,
            int writeBufferSize,
            final boolean onlyAsyncRW,
            final int callbackOffenderCount,
            int backlog,
            int echoResponderBufferSize,
            boolean useServerExecutor
            ) throws Exception {

        final AtomicInteger sessionId = new AtomicInteger();
        TcpServer.Builder tcpServerBuilder = new TcpServer.Builder(tcpReactor) {{
            withOnlyAsyncReadWrite(onlyAsyncRW);
        }}
                .withReadBufferSize(readBufferSize)
                .withWriteBufferSize(writeBufferSize)
                .withLocalAddress("localhost", localPort)
                .withBacklog(backlog)
                .withTcpClientListener(new TcpClientListener() {
                    @Override
                    public void onConnected(TcpClient tcpClient) {
                        counts.connectedOk.incrementAndGet();

                        int sid = sessionId.getAndIncrement();
                        tcpClient.setDebugId(sid + "-session");

                        if (sid < callbackOffenderCount) try {
                            report(String.format("%s connected, now delaying", tcpClient.getDebugId()));
                            Thread.sleep(10000);
                        } catch (InterruptedException ie) {
                            report("Interrupted");
                        }
                        else {
                            report(String.format("%s connected", tcpClient.getDebugId()));
                        }

                        new EchoResponder(tcpClient, counts, echoResponderBufferSize, useServerExecutor).start();
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

        tcpServer = tcpServerBuilder.build();
        tcpServer.bind();
    }

    InetSocketAddress getInetSocketAddress() throws IOException {
         return (InetSocketAddress) tcpServer.getLocalAddress();
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }

    @Override
    public void close() throws IOException {
        tcpServer.close();
    }
}