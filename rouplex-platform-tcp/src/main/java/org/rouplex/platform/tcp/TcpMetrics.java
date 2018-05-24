package org.rouplex.platform.tcp;

import com.codahale.metrics.*;

import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpMetrics {
    public final MetricRegistry tcpBrokerMetrics = new MetricRegistry();

    public final Counter syncAddTcpRWClientsCount;
    public final Counter asyncAddTcpRWClientsCount;
    public final Counter interestOpsCount;
    public final Histogram selectedKeysBuckets;
    public final Timer timeInsideSelectNano;
    public final Timer timeOutsideSelectNano;

    public TcpMetrics() {
        JmxReporter.forRegistry(tcpBrokerMetrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .inDomain("TcpReactor")
            .build().start();

        syncAddTcpRWClientsCount = tcpBrokerMetrics.counter("syncAddTcpRWClientsCount");
        asyncAddTcpRWClientsCount = tcpBrokerMetrics.counter("asyncAddTcpRWClientsCount");
        interestOpsCount = tcpBrokerMetrics.counter("interestOpsCount");
        selectedKeysBuckets = tcpBrokerMetrics.histogram("selectedKeysBuckets");
        timeInsideSelectNano = tcpBrokerMetrics.timer("timeInsideSelectNano");
        timeOutsideSelectNano = tcpBrokerMetrics.timer("timeOutsideSelectNano");
    }
}
