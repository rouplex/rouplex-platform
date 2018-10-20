package org.rouplex;

import com.codahale.metrics.*;

import java.util.concurrent.TimeUnit;

/**
 * Hiding this class here, so we can use it for debugging occasionally.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpMetrics {
    public final MetricRegistry tcpReactorMetrics = new MetricRegistry();

    public final Counter syncAddTcpRWClientsCount;
    public final Counter asyncAddTcpRWClientsCount;
    public final Counter interestOpsCount;
    public final Histogram selectedKeysBuckets;
    public final Timer timeInsideSelectNano;
    public final Timer timeOutsideSelectNano;

    public TcpMetrics() {
        JmxReporter.forRegistry(tcpReactorMetrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .inDomain("RouplexReactor")
            .build().start();

        syncAddTcpRWClientsCount = tcpReactorMetrics.counter("syncAddTcpRWClientsCount");
        asyncAddTcpRWClientsCount = tcpReactorMetrics.counter("asyncAddTcpRWClientsCount");
        interestOpsCount = tcpReactorMetrics.counter("interestOpsCount");
        selectedKeysBuckets = tcpReactorMetrics.histogram("selectedKeysBuckets");
        timeInsideSelectNano = tcpReactorMetrics.timer("timeInsideSelectNano");
        timeOutsideSelectNano = tcpReactorMetrics.timer("timeOutsideSelectNano");
    }
}
