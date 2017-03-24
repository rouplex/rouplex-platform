package org.rouplex.platform.tcp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.aspectj.lang.ProceedingJoinPoint;

import java.util.logging.Logger;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RouplexTcpBinderReporter {
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
        completeId = String.format(format,rouplexTcpBinder.hashCode());
        aggregatedId = completeId;
    }

    public String getAggregatedId() {
        return aggregatedId;
    }
}