package org.rouplex;

import org.junit.Assert;
import org.rouplex.platform.tcp.TcpClient;
import org.rouplex.platform.tcp.TcpClientListener;
import org.rouplex.platform.tcp.TcpReactor;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EchoClientGroup {
    final Counts counts = new Counts("clients");
    final CountDownLatch clientConnectionEvents;
    final int clientGroupSize;

    EchoClientGroup(
            TcpReactor tcpReactor,
            int clientGroupSize,
            boolean useExecutor,
            int readBufferSize,
            int writeBufferSize,
            final boolean onlyAsyncRW,
            final int callbackOffenderCount,
            final int echoBufferSize
    ) throws Exception {
        this.clientGroupSize = clientGroupSize;
        clientConnectionEvents = new CountDownLatch(2 * clientGroupSize - callbackOffenderCount);
        for (int i = 0; i < clientGroupSize; i++) {
            TcpClient.Builder tcpClientBuilder = new TcpClient.Builder(tcpReactor) {{
                withOnlyAsyncReadWrite(onlyAsyncRW);
            }}
                    .withRemoteAddress("localhost", 7777)
                    .withReadBufferSize(readBufferSize)
                    .withWriteBufferSize(writeBufferSize)
                    .withTcpClientListener(new TcpClientListener() {
                        @Override
                        public void onConnected(TcpClient tcpClient) {
                            clientConnectionEvents.countDown();
                            counts.connectedOk.incrementAndGet();
                            report(String.format("%s connected", tcpClient.getDebugId()));

                            byte[] payload = new byte[echoBufferSize];
                            String tag = "Hello from " + tcpClient.getDebugId();
                            System.arraycopy(tag.getBytes(), 0, payload, 0,
                                    Math.min(echoBufferSize, tag.length()));

                            Echoer echoer = new Echoer(tcpClient, counts);
                            echoer.write(ByteBuffer.wrap(payload), true, false);
                            echoer.read(false, true);
                        }

                        @Override
                        public void onConnectionFailed(TcpClient tcpClient, Exception reason) {
                            clientConnectionEvents.countDown();
                            clientConnectionEvents.countDown(); // simulate the connect/disconnect
                            counts.failedConnect.incrementAndGet();

                            report(String.format("%s failed connect (%s)", tcpClient.getDebugId(),
                                    reason.getClass().getSimpleName() + ": " + reason.getMessage()));
                        }

                        @Override
                        public void onDisconnected(TcpClient tcpClient, Exception optionalReason) {
                            clientConnectionEvents.countDown();
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
        }
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }

    void waitFinish() throws Exception {
        clientConnectionEvents.await(clientGroupSize * 10000000, TimeUnit.MILLISECONDS);
        Assert.assertEquals(0, clientConnectionEvents.getCount());
    }
}