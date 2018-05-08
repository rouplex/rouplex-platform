package org.rouplex.platform.tcp;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpMetrics {
    public final MetricRegistry tcpBrokerMetrics = new MetricRegistry();

    public final Histogram selectedKeysCounts;
    public final Timer timeInsideSelectNano;
    public final Timer timeOutsideSelectNano;

    public TcpMetrics() {
        JmxReporter.forRegistry(tcpBrokerMetrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .inDomain("tcpbroker")
            .build().start();

        selectedKeysCounts = tcpBrokerMetrics.histogram("selectedKeysCounts");
        timeInsideSelectNano = tcpBrokerMetrics.timer("timeInsideSelectNano");
        timeOutsideSelectNano = tcpBrokerMetrics.timer("timeOutsideSelectNano");
    }
}
