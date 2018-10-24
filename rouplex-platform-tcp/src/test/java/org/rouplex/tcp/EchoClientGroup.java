package org.rouplex.tcp;

import org.junit.Assert;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class EchoClientGroup {
    protected final Counts counts = new Counts("clients");
    protected final CountDownLatch connectionEvents;
    protected final int clientGroupSize;

    EchoClientGroup(
            TcpReactor tcpReactor,
            int remotePort,
            int clientGroupSize,
            boolean useExecutor,
            int readBufferSize,
            int writeBufferSize,
            final boolean onlyAsyncRW,
            final int callbackOffenderCount,
            final int payloadSize,
            final int echoRequesterBufferSize,
            final int delayBetweenSuccessiveConnectsMillis
    ) throws Exception {
        this.clientGroupSize = clientGroupSize;
        connectionEvents = new CountDownLatch(2 * clientGroupSize - callbackOffenderCount);
        for (int i = 0; i < clientGroupSize; i++) {
            TcpClient.Builder tcpClientBuilder = new TcpClient.Builder(tcpReactor) {{
                withOnlyAsyncReadWrite(onlyAsyncRW);
            }}
                    .withRemoteAddress("localhost", remotePort)
                    .withReadBufferSize(readBufferSize)
                    .withWriteBufferSize(writeBufferSize)
                    .withTcpClientListener(new TcpClientListener() {
                        @Override
                        public void onConnected(TcpClient tcpClient) {
                            connectionEvents.countDown();
                            counts.connectedOk.incrementAndGet();
                            report(String.format("%s connected", tcpClient.getDebugId()));

                            byte[] payload = new byte[payloadSize];
                            String tag = "Hello from " + tcpClient.getDebugId();
                            System.arraycopy(tag.getBytes(), 0, payload, 0,
                                    Math.min(payloadSize, tag.length()));

                            InputStream inputStream = new ByteArrayInputStream(payload);
                            new EchoRequester(tcpClient, counts, echoRequesterBufferSize, inputStream, null).start();
                        }

                        @Override
                        public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                            connectionEvents.countDown();
                            connectionEvents.countDown(); // simulate the connect/disconnect
                            counts.failedConnect.incrementAndGet();

                            report(String.format("%s failed connect (%s)", tcpClient.getDebugId(),
                                    reason.getClass().getSimpleName() + ": " + reason.getMessage()));
                        }

                        @Override
                        public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                            connectionEvents.countDown();
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
                tcpClientBuilder.withEventsExecutor(null);
            }

            TcpClient tcpClient = tcpClientBuilder.build();
            tcpClient.setDebugId(i + "-client");
            tcpClient.connect();
            if (delayBetweenSuccessiveConnectsMillis > 0) {
                Thread.sleep(delayBetweenSuccessiveConnectsMillis);
            }
        }
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }
}