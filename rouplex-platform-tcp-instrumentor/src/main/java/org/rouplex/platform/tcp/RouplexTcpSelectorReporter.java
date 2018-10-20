package org.rouplex.platform.tcp;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.aspectj.lang.ProceedingJoinPoint;

import java.util.ConcurrentModificationException;
import java.util.logging.Logger;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpSelectorReporter {
    private static final Logger logger = Logger.getLogger(RouplexTcpSelectorReporter.class.getSimpleName());
    public static final String format = "TcpSelector.%s"; // [Hash]

    public final TcpReactor.TcpSelector tcpSelector;
    public final AopInstrumentor aopInstrumentor;

    public Meter handleSelectedKey;

    public String aggregatedId;
    public String completeId;

    public RouplexTcpSelectorReporter(TcpReactor.TcpSelector rouplexTcpSedlector, AopInstrumentor aopInstrumentor) {
        this.tcpSelector = rouplexTcpSedlector;
        this.aopInstrumentor = aopInstrumentor;

        updateId();
    }

    public Object reportHandleSelectedKey(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();

        handleSelectedKey.mark();
        logger.info(String.format("handleSelectedKey %s", completeId));
        return result;
    }

    private void updateId() {
        completeId = String.format(format, tcpSelector.hashCode());
        aggregatedId = String.format(format,
            aopInstrumentor.aopConfig.aggregateTcpSelectors ? "A" : tcpSelector.hashCode());

        handleSelectedKey = aopInstrumentor.metricRegistry.meter(MetricRegistry.name(aggregatedId, "handleSelectedKey"));

        try {
            /**
             * This section is not perfect since it will always lump together all the TcpBinders. If fetching this
             * metric becomes important then chaining the gauges would be a solution (or maybe better understanding
             * yammer).
             */
            aopInstrumentor.metricRegistry.register(MetricRegistry.name(aggregatedId, "registeredSelectionKeys"),
                    new Gauge<Integer>() {
                        @Override
                        public Integer getValue() {
                            int totalKeys = 0;

                            try {
                                for (TcpReactor.TcpSelector tcpSelector : aopInstrumentor.tcpSelectors.keySet()) {
                                   //aaa totalKeys += tcpSelector.selector.keys().size();
                                }
                            } catch (ConcurrentModificationException cme) {
                                // no biggie return whatever, rather than synchronize and get on tests way
                            }

                            return totalKeys;
                        }
                    });
        } catch (RuntimeException re) {
            // read the comment at beginning of the block
        }

    }

    public Object reportHandleSelectedKeyException(ProceedingJoinPoint pjp) throws Throwable {
        Exception e = (Exception) pjp.getArgs()[0];
        logger.warning(String.format("handleSelectedKeyException %s : %s : %s",
            e.getClass().getSimpleName(), e.getMessage(), AopInstrumentor.getStackTrace(e)));
        return pjp.proceed();
    }

    public Object reportHandleSelectException(ProceedingJoinPoint pjp) throws Throwable {
        Exception e = (Exception) pjp.getArgs()[0];
        logger.warning(String.format("handleSelectException %s : %s : %s",
            e.getClass().getSimpleName(), e.getMessage(), AopInstrumentor.getStackTrace(e)));
        return pjp.proceed();
    }

    public String getAggregatedId() {
        return aggregatedId;
    }
}