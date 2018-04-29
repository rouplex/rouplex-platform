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
    // [TcpClient,TcpServer].[Local]:[Port]::[Remote]:[Port]

    public final TcpClient tcpClient;
    public final AopInstrumentor aopInstrumentor;

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

    public RouplexTcpClientReporter(TcpClient tcpClient, AopInstrumentor aopInstrumentor) {
        this.tcpClient = tcpClient;
        this.aopInstrumentor = aopInstrumentor;

        try {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) tcpClient.getLocalAddress();
            String localAddress = inetSocketAddress.getHostName();
            int localPort = inetSocketAddress.getPort();

            inetSocketAddress = (InetSocketAddress) tcpClient.getRemoteAddress();
            String remoteAddress = inetSocketAddress.getHostName();
            int remotePort = inetSocketAddress.getPort();

            AopConfig aopConfig = aopInstrumentor.aopConfig;
            String actor = tcpClient.getOriginatingTcpServer() == null ? "TcpClient" : "TcpServer";

            if (aopConfig.useShortFormat) {
                completeId = tcpClient.getOriginatingTcpServer() == null ? "C:" + localPort + "->S" : "S->C:" + remotePort;
            } else {
                completeId = String.format(format, actor, localAddress, localPort, remoteAddress, remotePort);
            }

            aggregatedId = String.format(format,
                actor,
                aopConfig.aggregateLocalAddresses ? "A" : localAddress,
                aopConfig.aggregateLocalPorts ? "A" : localPort,
                aopConfig.aggregateRemoteAddresses ? "A" : remoteAddress,
                aopConfig.aggregateRemotePorts ? "A" : remotePort
            );
        } catch (Exception e) {
            completeId = "A";
            aggregatedId = "A";
        }

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

    public Object reportHandleClose(ProceedingJoinPoint pjp) throws Throwable {
        Exception optionalException = (Exception) pjp.getArgs()[0];
        if (optionalException == null) {
            logger.warning(String.format("syncClose %s [graceful]", completeId));
        } else {
            logger.warning(String.format("syncClose %s [abrupt]. Cause: %s %s",
                completeId, optionalException.getClass().getSimpleName(), optionalException.getMessage()));
        }

        return pjp.proceed();
    }

    public Object reportThrottledSenderSend(ProceedingJoinPoint pjp) throws Throwable {
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
            logger.info(String.format("throttledSenderSend %s [disconnect]", completeId));
        } else if (payloadSize == 0) {
            logger.info(String.format("throttledSenderSend %s [eos]", completeId));
        } else {
            sentBytes.mark(payloadSize - payload.remaining());
            unsentBytes.mark(payload.remaining());
            logger.info(String.format("throttledSenderSend %s [%s bytes (%s remaining)]",
                completeId, payloadSize - payload.remaining(), payload.remaining()));
        }

        return result;
    }

    public Object reportThrottledSenderRemoveWriteBuffer(ProceedingJoinPoint pjp) throws Throwable {
        logger.info(String.format("removeWriteBuffer %s [entering]", completeId));
        Object result = pjp.proceed();
        logger.info(String.format("removeWriteBuffer %s [exiting]", completeId));
        return result;
    }

    public Object reportThrottledReceiverConsume(ProceedingJoinPoint pjp) throws Throwable {
        byte[] payload = (byte[]) pjp.getArgs()[0];

        if (payload == null) {
            receivedDisconnect.mark();
        } else if (payload.length == 0) {
            receivedEos.mark();
        }

        Object result = pjp.proceed();

        if (payload == null) {
            logger.info(String.format("throttledReceiverConsume %s [disconnect]", completeId));
        } else if (payload.length == 0) {
            logger.info(String.format("throttledReceiverConsume %s [eos]", completeId));
        } else {
            receivedBytes.mark(payload.length);
            logger.info(String.format("throttledReceiverConsume %s [%s bytes]", completeId, payload.length));
        }

        return result;
    }

    public String getAggregatedId() {
        return aggregatedId;
    }
}