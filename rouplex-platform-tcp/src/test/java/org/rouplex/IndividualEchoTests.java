package org.rouplex;

import org.junit.Test;
import org.rouplex.platform.tcp.TcpReactor;

public class IndividualEchoTests {

    /**
     * Shared tcpSelector, one client, synchronous callbacks, no buffering, 20 bytes exchange
     * @throws Exception
     */
    @Test
    public void test1() throws Exception {
        TcpReactor tcpReactor = new TcpReactor.Builder().withThreadCount(0).build();

        EchoServer echoServer = new EchoServer(tcpReactor, false, 0, 0, false, 0, 0);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, 10, false, 0, 0, false, 0, 20);
        echoClientGroup.waitFinish();

        echoServer.counts.report();
        echoClientGroup.counts.report();
    }

    /**
     * Shared tcpSelector, one client, synchronous callbacks, no buffering, 20kb exchange
     * @throws Exception
     */
    @Test
    public void test2() throws Exception {
        TcpReactor tcpReactor = new TcpReactor.Builder().withThreadCount(0).build();

        EchoServer echoServer = new EchoServer(tcpReactor, false, 0, 0, false, 0, 0);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, 1, false, 0, 0, false, 0, 20000);
        echoClientGroup.waitFinish();

        echoServer.counts.report();
        echoClientGroup.counts.report();
    }
}