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
public class RouplexTcpBinderReporter {
    private static final Logger logger = Logger.getLogger(RouplexTcpBinderReporter.class.getSimpleName());
    public static final String format = "RouplexTcpBinder.%s"; // [Hash]

    public final RouplexTcpBinder rouplexTcpBinder;
    public final AopInstrumentor aopInstrumentor;

    public Meter handleSelectedKey;

    public String aggregatedId;
    public String completeId;

    public RouplexTcpBinderReporter(RouplexTcpBinder rouplexTcpBinder, AopInstrumentor aopInstrumentor) {
        this.rouplexTcpBinder = rouplexTcpBinder;
        this.aopInstrumentor = aopInstrumentor;

        updateId();
    }

    public Object handleSelectedKey(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();

        handleSelectedKey.mark();
        logger.info(String.format("handleSelectedKey %s", completeId));
        return result;
    }

    private void updateId() {
        completeId = String.format(format, rouplexTcpBinder.hashCode());
        AopConfig aopConfig = aopInstrumentor.aopConfig;
        aggregatedId = String.format(format,
                aopConfig.aggregateTcpBinders ? "A" : rouplexTcpBinder.hashCode()
        );

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
                                for (RouplexTcpBinder tcpBinder : aopInstrumentor.tcpBinders.keySet()) {
                                    totalKeys += tcpBinder.selector.keys().size();
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

    public String getAggregatedId() {
        return aggregatedId;
    }
}