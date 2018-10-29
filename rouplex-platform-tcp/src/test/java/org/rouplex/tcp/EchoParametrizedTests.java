package org.rouplex.tcp;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.rouplex.platform.tcp.TcpReactor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
public class EchoParametrizedTests {
//    private static boolean[] secures = new boolean[] {true, false};
//
//    @Parameterized.Parameters
//    public static Collection<Object[]> data() {
//        List<Object[]> result = new ArrayList<>();
//
//        for (boolean secure : secures) {
//            result.add(new Object[] {
//                    new TcpReactor.Builder()
//                            .withSelectorProvider(selectorProvider)
//                            .withThreadCount(1)
//                            .build(),
//
//                    new EchoServer(tcpReactor)
//                            .withLocalPort(0)
//                            .withSecure(secure)
//                            .withUseEventsExecutor(false)
//                            .start();
//            });
//        }
//
//        return Arrays.asList(new Object[][] {
//                { 0, 0 }, { 1, 1 }, { 2, 1 }, { 3, 2 }, { 4, 3 }, { 5, 5 }, { 6, 8 }
//        });
//    }
//
//    private final TcpReactor tcpServerReactor;
//    private final EchoServer echoServer;
//    private final TcpReactor tcpClientGroupReactor;
//    private final EchoClientGroup echoClientGroup;
//
//    EchoParametrizedTests(TcpReactor tcpServerReactor, EchoServer echoServer, TcpReactor tcpClientGroupReactor, EchoClientGroup echoClientGroup) {
//        this.tcpServerReactor = tcpServerReactor;
//        this.echoServer = echoServer;
//
//        this.tcpClientGroupReactor = tcpClientGroupReactor;
//        this.echoClientGroup = echoClientGroup;
//    }
//
//    /**
//     * Success with:
//     *  Shared/Any tcpReactor, one/any selector thread, one/any client, synchronous/asynchronous callbacks, behaved on
//     *  callbacks, no/any buffering, 20/any bytes exchange
//     */
//    @Test
//    public void test1_SimplestUseCase() throws Exception {
//        TcpReactor tcpReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider)
//                .withThreadCount(1)
//                .build();
//
//        EchoServer echoServer = new EchoServer(tcpReactor)
//                .withLocalPort(0)
//                .withSecure(secure)
//                .withUseEventsExecutor(false)
//                .start();
//
//        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, 1)
//                .withRemoteAddress(echoServer.getInetSocketAddress())
//                .withSecure(secure)
//                .withUseEventsExecutor(false)
//                .withPayloadSize(20)
//                .start();
//
//        echoClientGroup.connectionEvents.await(10, TimeUnit.SECONDS);
//        echoClientGroup.echoCounts.report();
//        echoServer.echoCounts.report();
//
//        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
//    }
//
//    /**
//     * Failure with:
//     *  1 tcpReactor thread, 2 clients, synchronous callbacks, server/client non-behaved on callbacks
//     *
//     * When:
//     *  The server is not using the executor
//     *  The tcpServerReactor is using one thread
//     *  The first callback is delayed
//     * Then:
//     *  We observe two connected events on client group (ok)
//     *  We don't observe at least one disconnected event (ko)
//     *
//     * Because:
//     *  Blocked on first session, the server cannot handle the second session
//     */
//    @Test(expected = AssertionError.class)
//    public void test2_LongCallbackFailure() throws Exception {
//        TcpReactor tcpServerReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider).withThreadCount(1).build();
//        TcpReactor tcpClientReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider).withThreadCount(1).build();
//
//        int callbackOffenderCount = 1;
//        EchoServer echoServer = new EchoServer(tcpServerReactor)
//                .withLocalPort(0)
//                .withSecure(secure)
//                .withCallbackOffenderCount(callbackOffenderCount)
//                .withUseEventsExecutor(false)
//                .start();
//
//        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpClientReactor, 2)
//                .withRemoteAddress(echoServer.getInetSocketAddress())
//                .withSecure(secure)
//                .withCallbackOffenderCount(callbackOffenderCount)
//                .withUseEventsExecutor(false)
//                .withPayloadSize(20)
//                .start();
//
//        echoClientGroup.connectionEvents.await(2, TimeUnit.SECONDS);
//        echoClientGroup.echoCounts.report();
//        echoServer.echoCounts.report();
//
//        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
//    }
//
//    /**
//     * Success fixing failure of test2LongCallbackFailure with:
//     *  2 tcpReactor threads, 2 clients, synchronous callbacks, server/client non-behaved on callbacks
//     *
//     * When:
//     *  The server is not using the executor
//     *  The tcpServerReactor is using two threads (to have a second thread free)
//     *  The first callback is delayed
//     * Then:
//     *  We observe two connected events on client group (ok)
//     *  We only observe one disconnected event (ok)
//     * Because:
//     *  The server is delaying the callback on one thread which is blocking that particular client but the other thread
//     *  handles the second connection which does not get penalized
//     *
//     * Suggestion:
//     *  even if this looks like a solution, one would need to have as many threads as expected connections and that's
//     *  not an acceptable pattern in most cases
//     */
//    @Test
//    public void test2_1LongCallbackMitigatedByMoreReactorThreads() throws Exception {
//        TcpReactor tcpServerReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider).withThreadCount(2).build();
//        TcpReactor tcpClientReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider).withThreadCount(1).build();
//
//        int callbackOffenderCount = 1;
//        EchoServer echoServer = new EchoServer(tcpServerReactor)
//                .withLocalPort(0)
//                .withSecure(secure)
//                .withCallbackOffenderCount(callbackOffenderCount)
//                .withUseEventsExecutor(false)
//                .start();
//
//        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpClientReactor, 2)
//                .withRemoteAddress(echoServer.getInetSocketAddress())
//                .withSecure(secure)
//                .withCallbackOffenderCount(callbackOffenderCount)
//                .withUseEventsExecutor(false)
//                .withPayloadSize(20)
//                .start();
//
//        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);
//        echoClientGroup.echoCounts.report();
//        echoServer.echoCounts.report();
//
//        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
//    }
//
//    /**
//     * Success fixing failure of test2LongCallbackFailure with:
//     *  2 clients, asynchronous callbacks, server/client non-behaved on callbacks
//     *
//     * When:
//     *  The server is using the executor
//     *  The tcpServerReactor is using one thread
//     *  The first callback is delayed
//     * Then:
//     *  We observe two connected events on client group (ok)
//     *  We only observe one disconnected event (ok)
//     * Because:
//     *  The server is delaying the callback called from an executor thread which is blocking that particular client but
//     *  then another executor thread handles the second connection which does not get penalized.
//     *
//     * Suggestion:
//     *  this is the suggested solution, at the extra cost of thread context switch. No offending session would be able
//     *  to interfere with other sessions
//     */
//    @Test
//    public void test2_2LongCallbackMitigatedByExecutorUse() throws Exception {
//        TcpReactor tcpServerReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider).withThreadCount(1).build();
//        TcpReactor tcpClientReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider).withThreadCount(1).build();
//
//        int callbackOffenderCount = 1;
//        EchoServer echoServer = new EchoServer(tcpServerReactor)
//                .withLocalPort(0)
//                .withSecure(secure)
//                .withCallbackOffenderCount(callbackOffenderCount)
//                .withUseEventsExecutor(true)
//                .start();
//
//        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpClientReactor, 2)
//                .withRemoteAddress(echoServer.getInetSocketAddress())
//                .withSecure(secure)
//                .withCallbackOffenderCount(callbackOffenderCount)
//                .withUseEventsExecutor(false)
//                .withPayloadSize(20)
//                .start();
//
//        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);
//        echoClientGroup.echoCounts.report();
//        echoServer.echoCounts.report();
//
//        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
//    }
//
//    /**
//     * Failure with:
//     *  Small read buffer on EchoResponder
//     *
//     * When:
//     *  Small read buffer on EchoResponder (1:1000 ratio to data being sent)
//     *  Unlimited IO speed
//     * Then:
//     *  We observe a stack overflow
//     *
//     * Because:
//     *  The EchoResponder reads data from tcp stack, which it is able to write right away back to tcp stack, which in
//     *  turn clears the EchoResponder's buffer inviting a read on same thread and so on back and forth. This is not
//     *  due to implementation of TcpServer
//     */
//    @Test(expected = AssertionError.class)
//    public void test3_StackOverflowFailure() throws Exception {
//        TcpReactor tcpReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider).withThreadCount(1).build();
//
//        EchoServer echoServer = new EchoServer(tcpReactor)
//                .withLocalPort(0)
//                .withSecure(secure)
//                .withUseEventsExecutor(false)
//                .withEchoResponderBufferSize(1)
//                .start();
//
//        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, 1)
//                .withRemoteAddress(echoServer.getInetSocketAddress())
//                .withSecure(secure)
//                .withUseEventsExecutor(false)
//                .withPayloadSize(40000)
//                .start();
//
//        echoClientGroup.connectionEvents.await(1, TimeUnit.SECONDS);
//
//        echoClientGroup.echoCounts.report();
//        echoServer.echoCounts.report();
//
//        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
//    }
//
//    /**
//     * Success fixing failure of test3StackOverflowFailure with:
//     *  Executor in EchoResponder
//     *
//     * When:
//     *  Small read buffer on EchoResponder (1:1000 ratio to data being sent)
//     *  Unlimited IO speed
//     * Then:
//     *  All good
//     *
//     * Because:
//     *  TThe executor keeps the stack trace contained since every frame in it becomes a runnable in executor's queue
//     */
//    @Test
//    public void test3_1StackOverflowFailureMitigatedByExecutorUse() throws Exception {
//        TcpReactor tcpReactor = new TcpReactor.Builder()
//                .withSelectorProvider(selectorProvider)
//                .withThreadCount(1)
//                .build();
//
//        EchoServer echoServer = new EchoServer(tcpReactor)
//                .withLocalPort(0)
//                .withSecure(secure)
//                .withUseServerExecutor(true)
//                .withEchoResponderBufferSize(1)
//                .start();
//
//        EchoClientGroup echoClientGroup = new EchoClientGroup(tcpReactor, 1)
//                .withRemoteAddress(echoServer.getInetSocketAddress())
//                .withSecure(secure)
//                .withUseEventsExecutor(false)
//                .withPayloadSize(20000)
//                .start();
//
//        echoClientGroup.connectionEvents.await(2, TimeUnit.SECONDS);
//
//        echoClientGroup.echoCounts.report();
//        echoServer.echoCounts.report();
//
//        Assert.assertEquals(0, echoClientGroup.connectionEvents.getCount());
//    }
}