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
    public void testBrokerWithSharedExecutorShutdown() throws Exception {
        new RouplexTcpBroker(Selector.open(), Executors.newSingleThreadExecutor()).close();
    }

    @Test
    public void testServerWithNoBrokerShutdown() throws Exception {
        RouplexTcpServer.newBuilder().withLocalAddress(null, 9999).build().close();
    }

    @Test
    public void testServerWithNullBrokerShutdown() throws Exception {
        RouplexTcpServer.newBuilder().withRouplexBroker(null).withLocalAddress(null, 0).build().close();
    }

    @Test
    public void testServerWithSharedBrokerWithExecutorShutdown() throws Exception {
        RouplexTcpBroker rouplexBroker = new RouplexTcpBroker(Selector.open(), Executors.newSingleThreadExecutor());
        RouplexTcpServer.newBuilder().withRouplexBroker(rouplexBroker).withLocalAddress(null, 0).build().close();
        rouplexBroker.close();
    }

    @Test
    public void testServerWithSharedBrokerWithNullExecutorShutdown() throws Exception {
        RouplexTcpBroker rouplexBroker = new RouplexTcpBroker(Selector.open(), null);
        RouplexTcpServer.newBuilder().withRouplexBroker(rouplexBroker).withLocalAddress(null, 0).build().close();
        rouplexBroker.close();
    }

    @Test
    public void testServerClosesOnSharedBrokerShutdown() throws Exception {
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
    public void testServerClosesOnOwnedShutdown() throws Exception {
        RouplexTcpServer tcpServer = RouplexTcpServer.newBuilder().withLocalAddress(null, 0).build();
        tcpServer.getRouplexTcpBroker().close();

        for (int millis = 1; millis < 1000; millis *= 2) {
            if (tcpServer.isClosed()) {
                return;
            }

            Thread.sleep(millis);
        }

        Assert.assertTrue(tcpServer.isClosed());
    }


}