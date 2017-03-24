package org.rouplex.platform.tcp;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Aspect
public class AopInstrumentor {
    static Map<RouplexTcpBinder, RouplexTcpBinderReporter> tcpBinders
            = new ConcurrentHashMap<RouplexTcpBinder, RouplexTcpBinderReporter>();
    static Map<RouplexTcpClient, RouplexTcpClientReporter> tcpClients
            = new ConcurrentHashMap<RouplexTcpClient, RouplexTcpClientReporter>();

    static Field throttledSenderField;
    static {
        try {
            throttledSenderField = RouplexTcpClient.ThrottledSender.class.getDeclaredField("this$0");
            throttledSenderField.setAccessible(true);
        } catch (Exception e) {
        }
    }

    RouplexTcpBinderReporter getRouplexTcpBinderReporter(ProceedingJoinPoint pjp) {
        RouplexTcpBinder tcpBinder = ((RouplexTcpBinder) pjp.getThis());
        RouplexTcpBinderReporter tcpBinderReporter = tcpBinders.get(tcpBinder);
        if (tcpBinderReporter == null) {
            tcpBinders.putIfAbsent(tcpBinder, new RouplexTcpBinderReporter(tcpBinder));
            tcpBinderReporter = tcpBinders.get(tcpBinder);
        }

        return tcpBinderReporter;
    }

    RouplexTcpClientReporter getRouplexTcpClientReporter(ProceedingJoinPoint pjp) throws Exception {
        RouplexTcpClient.ThrottledSender throttledSender = ((RouplexTcpClient.ThrottledSender) pjp.getThis());
        RouplexTcpClient tcpClient = (RouplexTcpClient) throttledSenderField.get(throttledSender);
        RouplexTcpClientReporter tcpClientReporter = tcpClients.get(tcpClient);
        if (tcpClientReporter == null) {
            tcpClients.putIfAbsent(tcpClient, new RouplexTcpClientReporter(tcpClient));
            tcpClientReporter = tcpClients.get(tcpClient);
        }

        return tcpClientReporter;
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