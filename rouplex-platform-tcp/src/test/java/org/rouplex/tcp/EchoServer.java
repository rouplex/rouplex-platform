package org.rouplex.tcp;

import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;
import org.rouplex.platform.tcp.TcpServer;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class EchoServer implements Closeable {
    protected final EchoCounts echoCounts = new EchoCounts("server");
    protected final TcpReactor tcpReactor;
    protected final TcpServer.Builder tcpServerBuilder;
    protected TcpServer tcpServer;
    protected int callbackOffenderCount;
    protected int echoResponderBufferSize = 1000;
    protected boolean secure;
    protected boolean useServerExecutor;

    public EchoServer(TcpReactor tcpReactor) {
        this(tcpReactor, false);
    }

    public EchoServer(TcpReactor tcpReactor, final boolean onlyAsyncRW) {
        this.tcpReactor = tcpReactor;
        final AtomicInteger sessionId = new AtomicInteger();
        tcpServerBuilder = new TcpServer.Builder(tcpReactor) {{
            withOnlyAsyncReadWrite(onlyAsyncRW);
        }}
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
    }

    public EchoServer withLocalPort(int localPort) {
        tcpServerBuilder.withLocalAddress("localhost", localPort);
        return this;
    }

    public EchoServer withSecure(boolean secure) throws Exception {
        this.secure = secure;
        tcpServerBuilder.withSecure(secure ? SSLContext.getDefault() : null);
        return this;
    }

    public EchoServer withReadBufferSize(int readBufferSize) {
        tcpServerBuilder.withReadBufferSize(readBufferSize);
        return this;
    }

    public EchoServer withWriteBufferSize(int writeBufferSize) {
        tcpServerBuilder.withWriteBufferSize(writeBufferSize);
        return this;
    }

    public EchoServer withBacklog(int backlog) {
        tcpServerBuilder.withBacklog(backlog);
        return this;
    }

    public EchoServer withUseEventsExecutor(boolean useEventsExecutor) {
        tcpServerBuilder.withEventsExecutor(useEventsExecutor ? tcpReactor.getEventsExecutor() : null);
        return this;
    }

    public EchoServer withUseServerExecutor(boolean useServerExecutor) {
        this.useServerExecutor = useServerExecutor;
        return this;
    }

    public EchoServer withCallbackOffenderCount(int callbackOffenderCount) {
        this.callbackOffenderCount = callbackOffenderCount;
        return this;
    }

    public EchoServer withEchoResponderBufferSize(int echoResponderBufferSize) {
        this.echoResponderBufferSize = echoResponderBufferSize;
        return this;
    }

    public InetSocketAddress getInetSocketAddress() throws IOException {
        return (InetSocketAddress) tcpServer.getLocalAddress();
    }

    public EchoServer start() throws Exception {
        tcpServer = tcpServerBuilder.build();
        tcpServer.bind();
        return this;
    }

    @Override
    public void close() throws IOException {
        tcpServer.close();
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }
}