package org.rouplex.tcp;

import org.rouplex.commons.security.SecurityUtils;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

class EchoClientGroup {
    protected final EchoCounts echoCounts = new EchoCounts("clients");
    protected final TcpReactor tcpReactor;
    protected final List<TcpClient.Builder> tcpClientBuilders = new ArrayList<>();

    protected boolean secure;
    protected int payloadSize;
    protected int echoRequesterBufferSize = 1000;
    protected int callbackOffenderCount;
    protected int delayBetweenSuccessiveConnectsMillis;

    protected CountDownLatch connectionEvents;

    public EchoClientGroup(TcpReactor tcpReactor, int clientGroupSize) {
        this(tcpReactor, clientGroupSize, false);
    }

    public EchoClientGroup(TcpReactor tcpReactor, int clientGroupSize, final boolean onlyAsyncRW) {
        this.tcpReactor = tcpReactor;
        for (int i = 0; i < clientGroupSize; i++) {
            tcpClientBuilders.add(new TcpClient.Builder(tcpReactor) {{
                withOnlyAsyncReadWrite(onlyAsyncRW);
            }}
                    .withTcpClientListener(new TcpClientListener() {
                        @Override
                        public void onConnected(TcpClient tcpClient) {
                            connectionEvents.countDown();
                            echoCounts.connectedOk.incrementAndGet();
                            report(String.format("%s connected", tcpClient.getDebugId()));

                            byte[] payload = new byte[payloadSize];
                            String tag = "Hello from " + tcpClient.getDebugId();
                            System.arraycopy(tag.getBytes(), 0, payload, 0,
                                    Math.min(payloadSize, tag.length()));

                            InputStream inputStream = new ByteArrayInputStream(payload);
                            new EchoRequester(tcpClient, echoCounts, echoRequesterBufferSize, inputStream, null).start();
                        }

                        @Override
                        public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                            connectionEvents.countDown();
                            connectionEvents.countDown(); // simulate the connect/disconnect
                            echoCounts.failedConnect.incrementAndGet();

                            report(String.format("%s failed connect (%s)", tcpClient.getDebugId(),
                                    reason.getClass().getSimpleName() + ": " + reason.getMessage()));
                        }

                        @Override
                        public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                            connectionEvents.countDown();
                            if (optionalReason == null) {
                                echoCounts.disconnectedOk.incrementAndGet();
                            } else {
                                echoCounts.disconnectedKo.incrementAndGet();
                            }

                            report(String.format("%s disconnected (%s)", tcpClient.getDebugId(), optionalReason == null
                                    ? "ok" : optionalReason.getClass().getSimpleName() + ": " + optionalReason.getMessage()));
                        }
                    }));
        }
    }

    public EchoClientGroup withRemoteAddress(SocketAddress remoteAddress) {
        for (TcpClient.Builder tcpClientBuider : tcpClientBuilders) {
            tcpClientBuider.withRemoteAddress(remoteAddress);
        }
        return this;
    }

    public EchoClientGroup withSecure(boolean secure) {
        this.secure = secure;
        for (TcpClient.Builder tcpClientBuider : tcpClientBuilders) {
            tcpClientBuider.withSecure(secure ? SecurityUtils.buildRelaxedSSLContext(true, true) : null);
        }
        return this;
    }

    public EchoClientGroup withReadBufferSize(int readBufferSize) {
        for (TcpClient.Builder tcpClientBuider : tcpClientBuilders) {
            tcpClientBuider.withReadBufferSize(readBufferSize);
        }
        return this;
    }

    public EchoClientGroup withWriteBufferSize(int writeBufferSize) {
        for (TcpClient.Builder tcpClientBuider : tcpClientBuilders) {
            tcpClientBuider.withWriteBufferSize(writeBufferSize);
        }
        return this;
    }

    public EchoClientGroup withUseEventsExecutor(boolean useEventsExecutor) {
        for (TcpClient.Builder tcpClientBuider : tcpClientBuilders) {
            tcpClientBuider.withEventsExecutor(useEventsExecutor ? tcpReactor.getEventsExecutor() : null);
        }
        return this;
    }

    public EchoClientGroup withPayloadSize(int payloadSize) {
        this.payloadSize = payloadSize;
        return this;
    }

    public EchoClientGroup withEchoRequesterBufferSize(int echoRequesterBufferSize) {
        this.echoRequesterBufferSize = echoRequesterBufferSize;
        return this;
    }

    public EchoClientGroup withCallbackOffenderCount(int callbackOffenderCount) {
        this.callbackOffenderCount = callbackOffenderCount;
        return this;
    }

    public EchoClientGroup withDelayBetweenSuccessiveConnectsMillis(int delayBetweenSuccessiveConnectsMillis) {
        this.delayBetweenSuccessiveConnectsMillis = delayBetweenSuccessiveConnectsMillis;
        return this;
    }

    public EchoClientGroup start() throws Exception {
        connectionEvents = new CountDownLatch(2 * tcpClientBuilders.size() - callbackOffenderCount);
        int i = 0;
        for (TcpClient.Builder tcpClientBuider : tcpClientBuilders) {
            TcpClient tcpClient = tcpClientBuider.build();
            tcpClient.setDebugId(i++ + (secure ? "-secure-client" : "-client"));
            tcpClient.connect();
            if (delayBetweenSuccessiveConnectsMillis > 0) {
                Thread.sleep(delayBetweenSuccessiveConnectsMillis);
            }
        }

        return this;
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }
}