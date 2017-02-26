package org.rouplex;

import org.junit.Assert;
import org.junit.Test;
import org.rouplex.platform.tcp.RouplexTcpBroker;
import org.rouplex.platform.tcp.RouplexTcpServer;

import java.nio.channels.Selector;
import java.util.concurrent.Executors;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ShutdownTest {

    @Test
    public void testBrokerWithNullExecutorShutdown() throws Exception {
        new RouplexTcpBroker(Selector.open(), null).close();
    }

    @Test
    public void testBrokerWithExplicitExecutorShutdown() throws Exception {
        new RouplexTcpBroker(Selector.open(), Executors.newSingleThreadExecutor()).close();
    }

    @Test
    public void testServerWithImplicitBrokerShutdown() throws Exception {
        RouplexTcpServer.newBuilder().withLocalAddress(null, 0).build().close();
    }

    @Test
    public void testServerWithExplicitNullBrokerShutdown() throws Exception {
        RouplexTcpServer.newBuilder().withRouplexBroker(null).withLocalAddress(null, 0).build().close();
    }

    @Test
    public void testServerWithExplicitBrokerWithExecutorShutdown() throws Exception {
        RouplexTcpBroker rouplexBroker = new RouplexTcpBroker(Selector.open(), Executors.newSingleThreadExecutor());
        RouplexTcpServer.newBuilder().withRouplexBroker(rouplexBroker).withLocalAddress(null, 0).build().close();
        rouplexBroker.close();
    }

    @Test
    public void testServerClosesOnBrokerShutdown() throws Exception {
        RouplexTcpBroker rouplexBroker = new RouplexTcpBroker(Selector.open(), Executors.newSingleThreadExecutor());
        RouplexTcpServer tcpServer = RouplexTcpServer.newBuilder()
                .withRouplexBroker(rouplexBroker).withLocalAddress(null, 0).build();
        rouplexBroker.close();

        for (int millis = 1; millis < 1000; millis *= 2) {
            if (tcpServer.isClosed()) {
                return;
            }

            Thread.sleep(millis);
        }

        Assert.assertTrue(tcpServer.isClosed());
    }

    @Test
    public void testServerWithExplicitBrokerWithoutExecutorShutdown() throws Exception {
        RouplexTcpBroker rouplexBroker = new RouplexTcpBroker(Selector.open(), null);
        RouplexTcpServer.newBuilder().withRouplexBroker(rouplexBroker).withLocalAddress(null, 0).build().close();
        rouplexBroker.close();
    }
}