package org.rouplex.tcp;

import org.rouplex.commons.security.SecurityUtils;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;
import org.rouplex.platform.tcp.TcpServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

class EchoServer implements Closeable {
    protected final EchoCounts echoCounts = new EchoCounts("server");
    protected final TcpServer tcpServer;

    protected EchoServer(
            TcpReactor tcpReactor,
            int localPort,
            final boolean secure,
            boolean useExecutor,
            int readBufferSize,
            int writeBufferSize,
            final boolean onlyAsyncRW,
            final int callbackOffenderCount,
            int backlog,
            final int echoResponderBufferSize,
            final boolean useServerExecutor
    ) throws Exception {

        final AtomicInteger sessionId = new AtomicInteger();
        TcpServer.Builder tcpServerBuilder = new TcpServer.Builder(tcpReactor) {{
            withOnlyAsyncReadWrite(onlyAsyncRW);
        }}
                .withSecure(secure ? SSLContext.getDefault() : null)
                .withReadBufferSize(readBufferSize)
                .withWriteBufferSize(writeBufferSize)
                .withLocalAddress("localhost", localPort)
                .withBacklog(backlog)
                .withTcpClientListener(new TcpClientListener() {
                    @Override
                    public void onConnected(TcpClient tcpClient) {
                        echoCounts.connectedOk.incrementAndGet();

                        int sid = sessionId.getAndIncrement();
                        tcpClient.setDebugId(sid + (secure ? "-secure-session" : "-session"));

                        if (sid < callbackOffenderCount) try {
                            report(String.format("%s connected, now delaying", tcpClient.getDebugId()));
                            Thread.sleep(10000);
                        } catch (InterruptedException ie) {
                            report("Interrupted");
                        }
                        else {
                            report(String.format("%s connected", tcpClient.getDebugId()));
                        }

                        new EchoResponder(tcpClient, echoCounts, echoResponderBufferSize, useServerExecutor).start();
                    }

                    @Override
                    public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                        echoCounts.failedConnect.incrementAndGet();
                        report(String.format("%s failed connect", tcpClient.getDebugId()));
                    }

                    @Override
                    public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                        if (optionalReason == null) {
                            echoCounts.disconnectedOk.incrementAndGet();
                        } else {
                            echoCounts.disconnectedKo.incrementAndGet();
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