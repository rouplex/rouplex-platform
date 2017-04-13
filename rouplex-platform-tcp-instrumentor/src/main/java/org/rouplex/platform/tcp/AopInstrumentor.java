package org.rouplex.platform.tcp;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Aspect
public class AopInstrumentor {
    static Field throttledSenderField;
    static {
        try {
            throttledSenderField = RouplexTcpClient.ThrottledSender.class.getDeclaredField("this$0");
            throttledSenderField.setAccessible(true);
        } catch (Exception e) {
        }
    }

    public final AopConfig aopConfig = AopConfig.allAggregated();
    public final MetricRegistry metricRegistry = new MetricRegistry();
    public final Map<RouplexTcpBinder, RouplexTcpBinderReporter> tcpBinders
            = new ConcurrentHashMap<RouplexTcpBinder, RouplexTcpBinderReporter>();
    public final Map<RouplexTcpClient, RouplexTcpClientReporter> tcpClients
            = new ConcurrentHashMap<RouplexTcpClient, RouplexTcpClientReporter>();

    RouplexTcpBinderReporter getRouplexTcpBinderReporter(ProceedingJoinPoint pjp) {
        RouplexTcpBinder tcpBinder = ((RouplexTcpBinder) pjp.getThis());
        RouplexTcpBinderReporter tcpBinderReporter = tcpBinders.get(tcpBinder);
        if (tcpBinderReporter == null) {
            tcpBinders.putIfAbsent(tcpBinder, new RouplexTcpBinderReporter(tcpBinder, this));
            tcpBinderReporter = tcpBinders.get(tcpBinder);
        }

        return tcpBinderReporter;
    }

    RouplexTcpClientReporter getRouplexTcpClientReporter(ProceedingJoinPoint pjp) throws Exception {
        RouplexTcpClient.ThrottledSender throttledSender = ((RouplexTcpClient.ThrottledSender) pjp.getThis());
        RouplexTcpClient tcpClient = (RouplexTcpClient) throttledSenderField.get(throttledSender);
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

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpBinder.logHandleSelectedKeyException(..))")
    public Object logHandleSelectedKeyException(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpBinderReporter(pjp).logHandleSelectedKeyException(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpBinder.handleSelectedKey(..))")
    public Object handleSelectedKey(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpBinderReporter(pjp).handleSelectedKey(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpClient.ThrottledSender.send(..))")
    public Object throttledSenderSend(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpClientReporter(pjp).throttledSenderSend(pjp);
    }
}