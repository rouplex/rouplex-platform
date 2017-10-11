package org.rouplex.platform.tcp;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Aspect
public class AopInstrumentor {
    static Field throttledSenderField;
    static Field throttledReceiverField;
    static {
        try {
            throttledSenderField = RouplexTcpClient.ThrottledSender.class.getDeclaredField("this$0");
            throttledSenderField.setAccessible(true);

            throttledReceiverField = RouplexTcpClient.ThrottledReceiver.class.getDeclaredField("this$0");
            throttledReceiverField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(String.format("Could not initialize class. Cause: %s %s",
                e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    public final AopConfig aopConfig = AopConfig.shortFormat();
    public final MetricRegistry metricRegistry = new MetricRegistry();
    public final ConcurrentMap<RouplexTcpSelector, RouplexTcpSelectorReporter> tcpSelectors
            = new ConcurrentHashMap<RouplexTcpSelector, RouplexTcpSelectorReporter>();
    public final ConcurrentMap<RouplexTcpClient, RouplexTcpClientReporter> tcpClients
            = new ConcurrentHashMap<RouplexTcpClient, RouplexTcpClientReporter>();

    RouplexTcpSelectorReporter getRouplexTcpSelectorReporter(ProceedingJoinPoint pjp) {
        RouplexTcpSelector tcpSelector = ((RouplexTcpSelector) pjp.getThis());
        RouplexTcpSelectorReporter tcpSelectorReporter = tcpSelectors.get(tcpSelector);
        if (tcpSelectorReporter == null) {
            tcpSelectors.putIfAbsent(tcpSelector, new RouplexTcpSelectorReporter(tcpSelector, this));
            tcpSelectorReporter = tcpSelectors.get(tcpSelector);
        }

        return tcpSelectorReporter;
    }

    RouplexTcpClientReporter getRouplexTcpClientReporterOfSender(ProceedingJoinPoint pjp) throws Exception {
        RouplexTcpClient.ThrottledSender throttledSender = ((RouplexTcpClient.ThrottledSender) pjp.getThis());
        return getRouplexTcpClientReporter((RouplexTcpClient) throttledSenderField.get(throttledSender));
    }

    RouplexTcpClientReporter getRouplexTcpClientReporterOfReceiver(ProceedingJoinPoint pjp) throws Exception {
        RouplexTcpClient.ThrottledReceiver throttledReceiver = ((RouplexTcpClient.ThrottledReceiver) pjp.getThis());
        return getRouplexTcpClientReporter((RouplexTcpClient) throttledReceiverField.get(throttledReceiver));
    }

    RouplexTcpClientReporter getRouplexTcpClientReporter(ProceedingJoinPoint pjp) throws Exception {
        return getRouplexTcpClientReporter((RouplexTcpClient) pjp.getThis());
    }

    private RouplexTcpClientReporter getRouplexTcpClientReporter(RouplexTcpClient tcpClient) {
        RouplexTcpClientReporter tcpClientReporter = tcpClients.get(tcpClient);
        if (tcpClientReporter == null) {
            tcpClients.putIfAbsent(tcpClient, new RouplexTcpClientReporter(tcpClient, this));
            tcpClientReporter = tcpClients.get(tcpClient);
        }

        return tcpClientReporter;
    }

    AopInstrumentor() {
        JmxReporter.forRegistry(metricRegistry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build()
                .start();
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpSelector.handleSelectedKeyException(..))")
    public Object aroundHandleSelectedKeyException(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpSelectorReporter(pjp).reportHandleSelectedKeyException(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpSelector.handleSelectException(..))")
    public Object aroundHandleSelectException(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpSelectorReporter(pjp).reportHandleSelectException(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpSelector.handleSelectedKey(..))")
    public Object aroundHandleSelectedKey(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpSelectorReporter(pjp).reportHandleSelectedKey(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpClient.ThrottledSender.send(..))")
    public Object aroundThrottledSenderSend(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporterOfSender(pjp).reportThrottledSenderSend(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpClient.ThrottledSender.removeWriteBuffer(..))")
    public Object aroundThrottledSenderRemoveWriteBuffer(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporterOfSender(pjp).reportThrottledSenderRemoveWriteBuffer(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpClient.ThrottledReceiver.handleSocketInput(..))")
    public Object aroundThrottledReceiverConsume(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporterOfReceiver(pjp).reportThrottledReceiverConsume(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpEndPoint.syncClose(..))")
    public Object aroundHandleClose(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporter(pjp).reportHandleClose(pjp);
    }

    static String getStackTrace(Exception e) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(baos));
        return new String(baos.toByteArray()); // ascii string anyway
    }
}