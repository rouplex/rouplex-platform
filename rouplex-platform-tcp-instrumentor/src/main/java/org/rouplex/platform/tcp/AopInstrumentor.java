package org.rouplex.platform.tcp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Aspect
public class AopInstrumentor {
    Map<RouplexTcpBinder, RouplexTcpBinderReporter> socketChannelReporters
            = new ConcurrentHashMap<RouplexTcpBinder, RouplexTcpBinderReporter>();

    RouplexTcpBinderReporter getRouplexTcpBinderReporter(ProceedingJoinPoint pjp) {
        RouplexTcpBinder socketChannel = ((RouplexTcpBinder) pjp.getThis());
        RouplexTcpBinderReporter socketChannelReporter = socketChannelReporters.get(socketChannel);
        if (socketChannelReporter == null) {
            socketChannelReporters.putIfAbsent(socketChannel, new RouplexTcpBinderReporter(socketChannel));
            socketChannelReporter = socketChannelReporters.get(socketChannel);
        }

        return socketChannelReporter;
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpBinder.handleSelectedKey(..))")
    public Object handleSelectedKey(ProceedingJoinPoint pjp) throws Throwable {
        return getRouplexTcpBinderReporter(pjp).handleSelectedKey(pjp);
    }

    @Around("execution(* org.rouplex.platform.tcp.RouplexTcpBinder.kot(..))")
    public Object kot(ProceedingJoinPoint pjp) throws Throwable {
        System.out.println("plot");
        return pjp.proceed();
    }

    static class RouplexTcpBinderReporter {
        private static final Logger logger = Logger.getLogger(RouplexTcpBinderReporter.class.getSimpleName());
        private static final MetricRegistry benchmarkerMetrics = new MetricRegistry();
        public static final String format = "%s"; // [Hash]

        final RouplexTcpBinder rouplexTcpBinder;

        Meter handleSelectedKey;

        String aggregatedId;
        String completeId;

        public RouplexTcpBinderReporter(RouplexTcpBinder rouplexTcpBinder) {
            this.rouplexTcpBinder = rouplexTcpBinder;

            updateId();

            handleSelectedKey = benchmarkerMetrics.meter(MetricRegistry.name(aggregatedId, "handleSelectedKey"));
        }

        public Object handleSelectedKey(ProceedingJoinPoint pjp) throws Throwable {
            Object result = pjp.proceed();

            handleSelectedKey.mark();
            logger.info(String.format("handleSelectedKey %s", completeId));
            return result;
        }

        private void updateId() {
            completeId = String.format(format,
                    rouplexTcpBinder.hashCode());

            aggregatedId = completeId;
        }

        public String getAggregatedId() {
            return aggregatedId;
        }
    }
}