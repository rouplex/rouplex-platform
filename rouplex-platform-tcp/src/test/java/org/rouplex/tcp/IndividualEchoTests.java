package org.rouplex.tcp;

import org.junit.Assert;
import org.junit.Test;
import org.rouplex.platform.tcp.TcpReactor;

import java.util.concurrent.TimeUnit;

public class IndividualEchoTests {

    /**
     * Success with:
     *  Shared tcpReactor, one selector thread, one client, synchronous callbacks, behaved on callbacks, no buffering,
     *  20 bytes exchange
     */
    @Test
    public void test1SimplestUseCase() throws Exception {
        TcpReactor tcpReactor = new TcpReactor.Builder().withThreadCount(1).build();

        EchoServer echoServer = new EchoServer(tcpReactor, 0, false, 0, 0, false, 0, 0, 1000, false);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, echoServer.getInetSocketAddress().getPort(), 1, false, 0, 0, false, 0, 20, 1000, 0);

        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);
        echoClientGroup.counts.report();
        echoServer.counts.report();

        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
    }

    /**
     * Failure with:
     *  1 tcpReactor thread, 2 clients, synchronous callbacks, server or client non-behaved on callbacks
     *
     * When:
     *  The server is not using the executor
     *  The tcpServerReactor is using one thread
     *  The first callback is delayed
     * Then:
     *  We observe two connected events on client group (ok)
     *  We don't observe at least one disconnected event (ko)
     *  
     * Because:
     *  Blocked on first session, the server cannot handle the second session
     */
    @Test(expected = AssertionError.class)
    public void test2LongCallbackFailure() throws Exception {
        TcpReactor tcpServerReactor = new TcpReactor.Builder().withThreadCount(1).build();
        TcpReactor tcpClientReactor = new TcpReactor.Builder().withThreadCount(1).build();

        int callbackOffenderCount = 1;
        EchoServer echoServer = new EchoServer(tcpServerReactor, 0, false, 0, 0, false, callbackOffenderCount, 0, 1000, false);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpClientReactor, echoServer.getInetSocketAddress().getPort(), 2, false, callbackOffenderCount, 0, false, 0, 20, 1000, 0);

        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);
        echoClientGroup.counts.report();
        echoServer.counts.report();

        Assert.assertEquals(1, echoClientGroup.connectionEvents.getCount());
    }

    /**
     * Success fixing failure of test2LongCallbackFailure with:
     *  2 tcpReactor threads, 2 clients, synchronous callbacks, server or client non-behaved on callbacks
     *
     * When:
     *  The server is not using the executor
     *  The tcpServerReactor is using two threads (to have a second thread free)
     *  The first callback is delayed
     * Then:
     *  We observe two connected events on client group (ok)
     *  We only observe one disconnected event (ok)
     * Because:
     *  The server is delaying the callback on one thread which is blocking that particular client but the other thread
     *  handles the second connection which does not get penalized
     *
     * Suggestion:
     *  even if this looks like a solution, one would need to have as many threads as expected connections and that's
     *  not an acceptable pattern in most cases
     */
    @Test
    public void test2_1LongCallbackMitigatedByMoreReactorThreads() throws Exception {
        TcpReactor tcpServerReactor = new TcpReactor.Builder().withThreadCount(2).build();
        TcpReactor tcpClientReactor = new TcpReactor.Builder().withThreadCount(1).build();

        int callbackOffenderCount = 1;
        EchoServer echoServer = new EchoServer(tcpServerReactor, 0, false, 0, 0, false, callbackOffenderCount, 0, 1000, false);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpClientReactor, echoServer.getInetSocketAddress().getPort(), 2, false, callbackOffenderCount, 0, false, 0, 20, 1000, 0);

        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);
        echoClientGroup.counts.report();
        echoServer.counts.report();

        Assert.assertEquals(1, echoClientGroup.connectionEvents.getCount());
    }

    /**
     * Success fixing failure of test2LongCallbackFailure with:
     *  2 clients, asynchronous callbacks, server or client non-behaved on callbacks
     *
     * When:
     *  The server is using the executor
     *  The tcpServerReactor is using one thread
     *  The first callback is delayed
     * Then:
     *  We observe two connected events on client group (ok)
     *  We only observe one disconnected event (ok)
     * Because:
     *  The server is delaying the callback called from an executor thread which is blocking that particular client but
     *  then another executor thread handles the second connection which does not get penalized.
     *
     * Suggestion:
     *  this is the suggested solution, at the extra cost of thread context switch. No offending session would be able
     *  to interfere with other sessions
     */
    @Test
    public void test2_2LongCallbackMitigatedByExecutorUse() throws Exception {
        TcpReactor tcpServerReactor = new TcpReactor.Builder().withThreadCount(1).build();
        TcpReactor tcpClientReactor = new TcpReactor.Builder().withThreadCount(1).build();

        int callbackOffenderCount = 1;
        EchoServer echoServer = new EchoServer(tcpServerReactor, 0, true, 0, 0, false, callbackOffenderCount, 0, 1000, false);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpClientReactor, echoServer.getInetSocketAddress().getPort(), 2, false, callbackOffenderCount, 0, false, 0, 20, 1000, 0);

        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);
        echoClientGroup.counts.report();
        echoServer.counts.report();

        Assert.assertEquals(1, echoClientGroup.connectionEvents.getCount());
    }

    /**
     * Failure with:
     *  Small read buffer on EchoResponder
     *
     * When:
     *  Small read buffer on EchoResponder (1:1000 ratio to data being sent)
     *  Unlimited IO speed
     * Then:
     *  We observe a stack overflow
     *
     * Because:
     *  The EchoResponder reads data from tcp stack, which it is able to write right away back to tcp stack, which in
     *  turn clears the EchoResponder's buffer inviting a read on same thread and so on back and forth. This is not
     *  due to implementation of TcpServer
     */
    @Test(expected = AssertionError.class)
    public void test3StackOverflowFailure() throws Exception {
        TcpReactor tcpReactor = new TcpReactor.Builder().withThreadCount(1).build();

        EchoServer echoServer = new EchoServer(tcpReactor, 0, true, 0, 0, false, 0, 0, 1, false);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, echoServer.getInetSocketAddress().getPort(), 1, false, 0, 0, false, 0, 10000, 1000, 0);
        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);

        echoClientGroup.counts.report();
        echoServer.counts.report();

        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
    }

    /**
     * Success fixing failure of test3StackOverflowFailure with:
     *  Executor in EchoResponder
     *
     * When:
     *  Small read buffer on EchoResponder (1:1000 ratio to data being sent)
     *  Unlimited IO speed
     * Then:
     *  All good
     *
     * Because:
     *  TThe executor keeps the stack trace contained since every frame in it becomes a runnable in executor's queue
     */
    @Test
    public void test3_1StackOverflowFailureMitigatedByExecutorUse() throws Exception {
        TcpReactor tcpReactor = new TcpReactor.Builder().withThreadCount(1).build();

        EchoServer echoServer = new EchoServer(tcpReactor, 0, true, 0, 0, false, 0, 0, 1, true);
        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, echoServer.getInetSocketAddress().getPort(), 1, false, 0, 0, false, 0, 10000, 1000, 0);
        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);

        echoClientGroup.counts.report();
        echoServer.counts.report();

        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
    }
}