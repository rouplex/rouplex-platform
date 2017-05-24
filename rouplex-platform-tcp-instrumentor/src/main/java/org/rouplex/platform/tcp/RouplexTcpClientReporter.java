package org.rouplex.platform.tcp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.aspectj.lang.ProceedingJoinPoint;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpClientReporter {
    private static final Logger logger = Logger.getLogger(RouplexTcpClientReporter.class.getSimpleName());
    public static final String format = "%s.%s:%s::%s:%s";
    // [RouplexTcpClient,RouplexTcpServer].[Local]:[Port]::[Remote]:[Port]

    public final RouplexTcpClient rouplexTcpClient;
    public final AopInstrumentor aopInstrumentor;

    public String actor;

    public String remoteAddress;
    public String remotePort;
    public String localAddress;
    public String localPort;

    public Meter sentBytes;
    public Meter unsentBytes;
    public Meter innerSentBytes;
    public Meter sentEos;
    public Meter innerSentEos;
    public Meter sentDisconnect;

    public Meter receivedBytes;
    public Meter receivedEos;
    public Meter receivedDisconnect;

    public String aggregatedId;
    public String completeId;

    public RouplexTcpClientReporter(RouplexTcpClient rouplexTcpClient, AopInstrumentor aopInstrumentor) {
        this.rouplexTcpClient = rouplexTcpClient;
        this.aopInstrumentor = aopInstrumentor;
        actor = rouplexTcpClient.getRouplexTcpServer() == null ? "RouplexTcpClient" : "RouplexTcpServer";

        try {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) rouplexTcpClient.getLocalAddress();
            localAddress = inetSocketAddress.getHostName();
            localPort = inetSocketAddress.getPort() + "";

            inetSocketAddress = (InetSocketAddress) rouplexTcpClient.getRemoteAddress();
            remoteAddress = inetSocketAddress.getHostName();
            remotePort = inetSocketAddress.getPort() + "";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        update();
    }

    public Object throttledSenderSend(ProceedingJoinPoint pjp) throws Throwable {
        ByteBuffer payload = (ByteBuffer) pjp.getArgs()[0];
        int payloadSize = 0;

        if (payload == null) {
            sentDisconnect.mark();
        } else if (!payload.hasRemaining()) {
            sentEos.mark();
        } else {
            payloadSize = payload.remaining();
        }

        Object result = pjp.proceed();

        if (payload == null) {
            logger.info(String.format("throttledSenderSend %s [sentDisconnect]", completeId));
        } else if (payloadSize == 0) {
            logger.info(String.format("throttledSenderSend %s [sentEos]", completeId));
        } else {
            sentBytes.mark(payloadSize - payload.remaining());
            unsentBytes.mark(payload.remaining());
            logger.info(String.format("throttledSenderSend %s [%s bytes (%s remaining)]",
                    completeId, payloadSize - payload.remaining(), payload.remaining()));
        }

        return result;
    }

    private void update() {
        completeId = String.format(format,
                actor,
                localAddress,
                localPort,
                remoteAddress,
                remotePort
        );

        AopConfig aopConfig = aopInstrumentor.aopConfig;
        aggregatedId = String.format(format,
                actor,
                aopConfig.aggregateLocalAddresses ? "A" : localAddress,
                aopConfig.aggregateLocalPorts? "A" : localPort,
                aopConfig.aggregateRemoteAddresses ? "A" : remoteAddress,
                aopConfig.aggregateRemotePorts ? "A" : remotePort
        );

        MetricRegistry metricRegistry = aopInstrumentor.metricRegistry;
        sentBytes = metricRegistry.meter(MetricRegistry.name(aggregatedId, "sentBytes"));
        unsentBytes = metricRegistry.meter(MetricRegistry.name(aggregatedId, "unsentBytes"));
        innerSentBytes = metricRegistry.meter(MetricRegistry.name(aggregatedId, "innerSentBytes"));
        sentEos = metricRegistry.meter(MetricRegistry.name(aggregatedId, "sentEos"));
        innerSentEos = metricRegistry.meter(MetricRegistry.name(aggregatedId, "innerSentEos"));
        sentDisconnect = metricRegistry.meter(MetricRegistry.name(aggregatedId, "sentDisconnect"));

        receivedBytes = metricRegistry.meter(MetricRegistry.name(aggregatedId, "receivedBytes"));
        receivedEos = metricRegistry.meter(MetricRegistry.name(aggregatedId, "receivedEos"));
        receivedDisconnect = metricRegistry.meter(MetricRegistry.name(aggregatedId, "receivedDisconnect"));
    }

    public String getAggregatedId() {
        return aggregatedId;
    }
}