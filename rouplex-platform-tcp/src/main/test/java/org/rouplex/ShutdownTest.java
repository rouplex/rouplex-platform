package org.rouplex;

import org.junit.Assert;
import org.junit.Test;
import org.rouplex.platform.tcp.RouplexTcpBinder;
import org.rouplex.platform.tcp.RouplexTcpServer;

import java.nio.channels.Selector;
import java.util.concurrent.Executors;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ShutdownTest {

    @Test
    public void testBinderWithNullExecutorShutdown() throws Exception {
        new RouplexTcpBinder(Selector.open(), null).close();
    }

    @Test
    public void testBinderWithSharedExecutorShutdown() throws Exception {
        new RouplexTcpBinder(Selector.open(), Executors.newSingleThreadExecutor()).close();
    }

    @Test
    public void testServerWithNoBinderShutdown() throws Exception {
        RouplexTcpServer.newBuilder().withLocalAddress(null, 9999).build().close();
    }

    @Test
    public void testServerWithNullBinderShutdown() throws Exception {
        RouplexTcpServer.newBuilder().withRouplexTcpBinder(null).withLocalAddress(null, 0).build().close();
    }

    @Test
    public void testServerWithSharedBinderWithExecutorShutdown() throws Exception {
        RouplexTcpBinder rouplexBinder = new RouplexTcpBinder(Selector.open(), Executors.newSingleThreadExecutor());
        RouplexTcpServer.newBuilder().withRouplexTcpBinder(rouplexBinder).withLocalAddress(null, 0).build().close();
        rouplexBinder.close();
    }

    @Test
    public void testServerWithSharedBinderWithNullExecutorShutdown() throws Exception {
        RouplexTcpBinder rouplexBinder = new RouplexTcpBinder(Selector.open(), null);
        RouplexTcpServer.newBuilder().withRouplexTcpBinder(rouplexBinder).withLocalAddress(null, 0).build().close();
        rouplexBinder.close();
    }

    @Test
    public void testServerClosesOnSharedBinderShutdown() throws Exception {
        RouplexTcpBinder rouplexBinder = new RouplexTcpBinder(Selector.open(), Executors.newSingleThreadExecutor());
        RouplexTcpServer tcpServer = RouplexTcpServer.newBuilder()
                .withRouplexTcpBinder(rouplexBinder).withLocalAddress(null, 0).build();
        rouplexBinder.close();

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
        tcpServer.getRouplexTcpBinder().close();

        for (int millis = 1; millis < 1000; millis *= 2) {
            if (tcpServer.isClosed()) {
                return;
            }

            Thread.sleep(millis);
        }

        Assert.assertTrue(tcpServer.isClosed());
    }


}