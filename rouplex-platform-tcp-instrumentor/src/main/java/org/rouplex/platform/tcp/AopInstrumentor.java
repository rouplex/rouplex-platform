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
//            throttledSenderField = TcpClient.ThrottledSender.class.getDeclaredField("this$0");
//            throttledSenderField.setAccessible(true);
//
//            throttledReceiverField = TcpClient.ThrottledReceiver.class.getDeclaredField("this$0");
//            throttledReceiverField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(String.format("Could not initialize class. Cause: %s %s",
                e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    public final AopConfig aopConfig = AopConfig.shortFormat();
    public final MetricRegistry metricRegistry = new MetricRegistry();
    public final ConcurrentMap<TcpReactor.TcpSelector, RouplexTcpSelectorReporter> tcpSelectors
            = new ConcurrentHashMap<TcpReactor.TcpSelector, RouplexTcpSelectorReporter>();
    public final ConcurrentMap<TcpClient, RouplexTcpClientReporter> tcpClients
            = new ConcurrentHashMap<TcpClient, RouplexTcpClientReporter>();

    RouplexTcpSelectorReporter getRouplexTcpSelectorReporter(ProceedingJoinPoint pjp) {
        TcpReactor.TcpSelector tcpSelector = ((TcpReactor.TcpSelector) pjp.getThis());
        RouplexTcpSelectorReporter tcpSelectorReporter = tcpSelectors.get(tcpSelector);
        if (tcpSelectorReporter == null) {
            tcpSelectors.putIfAbsent(tcpSelector, new RouplexTcpSelectorReporter(tcpSelector, this));
            tcpSelectorReporter = tcpSelectors.get(tcpSelector);
        }

        return tcpSelectorReporter;
    }

    RouplexTcpClientReporter getRouplexTcpClientReporterOfSender(ProceedingJoinPoint pjp) throws Exception {
//        TcpClient.ThrottledSender throttledSender = ((TcpClient.ThrottledSender) pjp.getThis());
//        return getRouplexTcpClientReporter((TcpClient) throttledSenderField.get(throttledSender));
        return null;
    }

    RouplexTcpClientReporter getRouplexTcpClientReporterOfReceiver(ProceedingJoinPoint pjp) throws Exception {
//        TcpClient.ThrottledReceiver throttledReceiver = ((TcpClient.ThrottledReceiver) pjp.getThis());
//        return getRouplexTcpClientReporter((TcpClient) throttledReceiverField.get(throttledReceiver));
        return null;
    }

    RouplexTcpClientReporter getRouplexTcpClientReporter(ProceedingJoinPoint pjp) throws Exception {
        return getRouplexTcpClientReporter((TcpClient) pjp.getThis());
    }

    private RouplexTcpClientReporter getRouplexTcpClientReporter(TcpClient tcpClient) {
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

    @Around("execution(* org.rouplex.platform.tcp.TcpSelector.handleSelectedKeyException(..))")
    public Object aroundHandleSelectedKeyException(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpSelectorReporter(pjp).reportHandleSelectedKeyException(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.TcpSelector.handleSelectException(..))")
    public Object aroundHandleSelectException(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpSelectorReporter(pjp).reportHandleSelectException(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.TcpSelector.handleSelectedKey(..))")
    public Object aroundHandleSelectedKey(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpSelectorReporter(pjp).reportHandleSelectedKey(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.TcpClient.ThrottledSender.send(..))")
    public Object aroundThrottledSenderSend(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporterOfSender(pjp).reportThrottledSenderSend(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.TcpClient.ThrottledSender.removeWriteBuffer(..))")
    public Object aroundThrottledSenderRemoveWriteBuffer(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporterOfSender(pjp).reportThrottledSenderRemoveWriteBuffer(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.TcpClient.ThrottledReceiver.handleSocketInput(..))")
    public Object aroundThrottledReceiverConsume(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporterOfReceiver(pjp).reportThrottledReceiverConsume(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.TcpEndPoint.syncClose(..))")
    public Object aroundHandleClose(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporter(pjp).reportHandleClose(pjp);
    }

    static String getStackTrace(Exception e) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        e.printStackTrace(new PrintStream(baos));
        return new String(baos.toByteArray()); // ascii string anyway
    }
}