package org.rouplex;

import com.codahale.metrics.*;
import org.rouplex.platform.rr.EventListener;
import org.rouplex.platform.rr.ReceiveChannel;
import org.rouplex.platform.rr.SendChannel;
import org.rouplex.platform.rr.Throttle;
import org.rouplex.platform.tcp.RouplexTcpBroker;
import org.rouplex.platform.tcp.RouplexTcpClient;
import org.rouplex.platform.tcp.RouplexTcpServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ClientServerTest implements Closeable {
    final RouplexTcpBroker sharedRouplexTcpBroker;
    final Map<String, RouplexTcpServer> rouplexTcpServers = new HashMap<String, RouplexTcpServer>();
    final Set<RouplexTcpClient> rouplexTcpClients = new HashSet<RouplexTcpClient>();
    final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    final Random random = new Random();
    final MetricRegistry clientServerMetrics = new MetricRegistry();
    ConsoleReporter reporter;


    public static void main(String[] args) throws Exception {
        ClientServerTest clientServerTest = new ClientServerTest();
        clientServerTest.startReport();

        // Start server
        StartTcpServerRequest startTcpServerRequest = new StartTcpServerRequest();
        startTcpServerRequest.port = 9999;
        RouplexTcpServer rouplexTcpServer = clientServerTest.startTcpServer(startTcpServerRequest);
        InetSocketAddress inetSocketAddress = (InetSocketAddress) rouplexTcpServer.getLocalAddress();

        // Start clients
        RunTcpClientsRequest runTcpClientsRequest = new RunTcpClientsRequest();
        runTcpClientsRequest.hostname = inetSocketAddress.getHostName();
        runTcpClientsRequest.port = inetSocketAddress.getPort();
        runTcpClientsRequest.clientCount = 1000;

        runTcpClientsRequest.minClientLifeMillis = 10000000;
        runTcpClientsRequest.minDelayMillisBeforeCreatingClient = 10;
        runTcpClientsRequest.minDelayMillisBetweenSends = 10;
        runTcpClientsRequest.minPayloadSize = 10000;

        runTcpClientsRequest.maxClientLifeMillis = 10000001;
        runTcpClientsRequest.maxDelayMillisBeforeCreatingClient = 11;
        runTcpClientsRequest.maxDelayMillisBetweenSends = 11;
        runTcpClientsRequest.maxPayloadSize = 10001;

        clientServerTest.runTcpClientsRequest(runTcpClientsRequest);

        // Wait for clients to finish
        Thread.sleep(runTcpClientsRequest.maxDelayMillisBeforeCreatingClient + runTcpClientsRequest.maxClientLifeMillis);

        // Close all
        clientServerTest.close();
    }

    ClientServerTest() throws IOException {
        sharedRouplexTcpBroker = new RouplexTcpBroker(Selector.open(), null);
    }

    public RouplexTcpServer startTcpServer(StartTcpServerRequest request) throws IOException {
        RouplexTcpServer rouplexTcpServer = RouplexTcpServer.newBuilder()
                .withLocalAddress(request.hostname, request.port)
                .build();

        InetSocketAddress inetSocketAddress = (InetSocketAddress) rouplexTcpServer.getLocalAddress();
        final String hostPort = String.format("%s:%s", inetSocketAddress.getHostName(), inetSocketAddress.getPort());
        rouplexTcpServers.put(hostPort, rouplexTcpServer);

        rouplexTcpServer.getRouplexTcpBroker().setTcpClientAddedListener(new EventListener<RouplexTcpClient>() {
            @Override
            public void onEvent(RouplexTcpClient rouplexTcpClient) {
                new EchoResponder(rouplexTcpClient, hostPort);
            }
        });

        return rouplexTcpServer;
    }

    class EchoResponder {
        final SendChannel<ByteBuffer> sendChannel;
        final Throttle receiveThrottle;

        final Meter addedClients;
        final Meter removedClients;
        final Meter receivedBytes;
        final Meter sentBytes;
        final Histogram incomingMessageSizes;

        ByteBuffer sendBuffer;

        EchoResponder(RouplexTcpClient rouplexTcpClient, String hostPort) {
            String clientId = rouplexTcpClient.hashCode() + "";
            addedClients = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "server", hostPort, "added", "client", clientId));
            removedClients = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "server", hostPort, "removed", "client", clientId));
            receivedBytes = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "server", hostPort, "received", "client", clientId));
            sentBytes = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "server", hostPort, "sent", "client", clientId));
            incomingMessageSizes = clientServerMetrics.histogram(MetricRegistry.name(EchoResponder.class, "server", hostPort, "incomingMessageSizes", "client", clientId));

            addedClients.mark();

            sendChannel = rouplexTcpClient.hookSendChannel(new Throttle() {
                @Override
                public void resume() {
                    send();
                }
            });

            receiveThrottle = rouplexTcpClient.hookReceiveChannel(new ReceiveChannel<byte[]>() {
                @Override
                public boolean receive(byte[] payload) {
                    if (payload == null) {
                        removedClients.mark();
                        return sendChannel.send(null);
                    }

                    // incomingMessageSizes.update(payload.length);
                    receivedBytes.mark(payload.length);
                    sendBuffer = ByteBuffer.wrap(payload);
                    return send();
                }
            });
        }

        private boolean send() {
            int position = sendBuffer.position();
            boolean sent = sendChannel.send(sendBuffer); // echo
            sentBytes.mark(sendBuffer.position() - position);
            return sent;
        }
    }

    public void runTcpClientsRequest(final RunTcpClientsRequest request) throws IOException {
        final Meter createdClients = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "created", "client"));
        final Meter failedCreationClients = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "uncreated", "client"));
        sharedRouplexTcpBroker.setTcpClientAddedListener(new EventListener<RouplexTcpClient>() {
            @Override
            public void onEvent(RouplexTcpClient rouplexTcpClient) {
                new EchoRequester(rouplexTcpClient, request);
            }
        });

        for (int cc = 0; cc < request.clientCount; cc++) {
            long startClientMillis = request.minDelayMillisBeforeCreatingClient +
                    random.nextInt(request.maxDelayMillisBeforeCreatingClient - request.minDelayMillisBeforeCreatingClient);

            scheduledExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        RouplexTcpClient.newBuilder()
                                .withRouplexBroker(sharedRouplexTcpBroker)
                                .withRemoteAddress(request.hostname, request.port)
                                .build();
                        createdClients.mark();
                    } catch (IOException ioe) {
                        failedCreationClients.mark();
                    }
                }
            }, startClientMillis, TimeUnit.MILLISECONDS);
        }
    }

    class EchoRequester {
        final RunTcpClientsRequest request;
        ByteBuffer sendBuffer;
        final SendChannel<ByteBuffer> sendChannel;
        final Throttle receiveThrottle;

        final Meter addedClients;
        final Meter removedClients;
        final Meter receivedBytes;
        final Meter sentBytes;

        EchoRequester(RouplexTcpClient rouplexTcpClient, RunTcpClientsRequest request) {
            this.request = request;
            rouplexTcpClients.add(rouplexTcpClient);

            String clientId = rouplexTcpClient.hashCode() + "";
            addedClients = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "client", clientId, "added"));
            removedClients = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "client", clientId, "removed"));
            sentBytes = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "client", clientId, "sent"));
            receivedBytes = clientServerMetrics.meter(MetricRegistry.name(EchoResponder.class, "client", clientId, "received"));

            addedClients.mark();

            sendChannel = rouplexTcpClient.hookSendChannel(new Throttle() {
                @Override
                public void resume() {
                    send();
                }
            });

            receiveThrottle = rouplexTcpClient.hookReceiveChannel(new ReceiveChannel<byte[]>() {
                @Override
                public boolean receive(byte[] payload) {
                    if (payload != null) {
                        receivedBytes.mark(payload.length);
                    } else {
                        removedClients.mark();
                    }

                    return true;
                }
            });

            scheduledExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    sendChannel.send(null); // send EOS
                }
            }, request.maxClientLifeMillis, TimeUnit.MILLISECONDS); // finish after a while

            send();
        }

        private boolean send() {
            if (sendBuffer == null) {
                int payloadSize = request.minPayloadSize + random.nextInt(request.maxPayloadSize - request.minPayloadSize);
                sendBuffer = ByteBuffer.allocate(payloadSize);
            }

            int position = sendBuffer.position();
            boolean sent = sendChannel.send(sendBuffer);
            sentBytes.mark(sendBuffer.position() - position);

            if (sent) {
                long sendDataMillis = request.minDelayMillisBetweenSends +
                        random.nextInt(request.maxDelayMillisBetweenSends - request.minDelayMillisBetweenSends);
                scheduledExecutor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        sendBuffer.clear(); // simulate new content
                        send();
                    }
                }, sendDataMillis, TimeUnit.MILLISECONDS);
            }

            return sent;
        }
    }

    private void startReport() {
        reporter = ConsoleReporter.forRegistry(clientServerMetrics)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        reporter.report();
        reporter.start(3, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        for (Map.Entry<String, RouplexTcpServer> entry : rouplexTcpServers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        for(RouplexTcpClient client : rouplexTcpClients) {
            try {
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        scheduledExecutor.shutdownNow();
        sharedRouplexTcpBroker.close();
        reporter.close();
    }

    public static class StartTcpServerRequest {
        String hostname;
        int port;
        boolean ssl;
    }

    public static class RunTcpClientsRequest {
        public String hostname;
        public int port;
        public boolean ssl;

        public int clientCount = 100;

        public int minPayloadSize;
        public int maxPayloadSize = 10000;
        public int minDelayMillisBetweenSends;
        public int maxDelayMillisBetweenSends = 1000;
        public int minDelayMillisBeforeCreatingClient;
        public int maxDelayMillisBeforeCreatingClient = 10000;
        public int minClientLifeMillis;
        public int maxClientLifeMillis = 10000;
    }
}
