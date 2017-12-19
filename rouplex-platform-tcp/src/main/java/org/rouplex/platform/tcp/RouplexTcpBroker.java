package org.rouplex.platform.tcp;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The starting place for configuring and creating tcp endpoints, an instance of this class provides the builders for
 * {@link RouplexTcpClient} and {@link RouplexTcpServer} instances.
 *
 * Multiple {@link RouplexTcpSelector} are used internally, each performing channel selections on a subset of the
 * endpoints for maximum use of processing resources. The suggested usage is to create just one instance of this class,
 * then subsequently create all tcp clients or servers starting from that instance. Though not necessary, more than one
 * instance of this class can be created, for example if you must use more than one {@link SelectorProvider}.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpBroker implements Closeable {
    private final RouplexTcpSelector[] tcpSelectors;
    private final AtomicInteger tcpSelectorIndex = new AtomicInteger();
    private boolean closed;

    /**
     * Construct an instance using the specified {@link SelectorProvider}, the default read buffer size.
     *
     * @param selectorProvider
     *          the provider to be used to create the {@link Selector} instances. Expect it to be called once per cpu
     *          core available.
     * @throws IOException
     *          if the instance could not be created, due to to a problem creating the selector or similar
     */
    public RouplexTcpBroker(SelectorProvider selectorProvider) throws IOException {
        this(selectorProvider, 1024 * 1024);
    }

    /**
     * Construct an instance using the specified {@link SelectorProvider}, {@link ThreadFactory} and read buffer size.
     *
     * @param selectorProvider
     *          the provider to be used to create the {@link Selector} instances. Expect it to be called once per cpu
     *          core available.
     * @param readBufferSize
     *          a positive value indicating how big the read buffer size should be.
     * @throws IOException
     *          if the instance could not be created, due to to a problem creating the selector or similar
     */
    public RouplexTcpBroker(SelectorProvider selectorProvider, int readBufferSize) throws IOException {
        if (readBufferSize <= 0) {
            throw new IllegalArgumentException("Read buffer size must be positive");
        }

        tcpSelectors = new RouplexTcpSelector[Runtime.getRuntime().availableProcessors()];
        for (int index = 0; index < tcpSelectors.length; index++) {
            tcpSelectors[index] = new RouplexTcpSelector(this, selectorProvider.openSelector(), readBufferSize);
            Thread thread = new Thread(tcpSelectors[index]);
            thread.setDaemon(true);
            thread.setName("RouplexTcpBroker-" + hashCode() + "-" + tcpSelectorIndex.incrementAndGet());
            thread.start();
        }
    }

    /**
     * Create a new builder to be used to build a RouplexTcpClient.
     *
     * @return
     *          the new tcp client builder
     */
    public RouplexTcpClient.Builder newRouplexTcpClientBuilder() {
        return new RouplexTcpClient.Builder(this);
    }

    /**
     * Create a new builder to be used to build a RouplexTcpServer.
     *
     * @return
     *          the new tcp server builder
     */
    public RouplexTcpServer.Builder newRouplexTcpServerBuilder() {
        return new RouplexTcpServer.Builder(this);
    }

    /**
     * We assign each created channel to the next {@link RouplexTcpSelector} in a round-robin fashion.
     *
     * @return
     *          the next RouplexTcpSelector to be used
     */
    RouplexTcpSelector nextRouplexTcpSelector() {
        return tcpSelectors[tcpSelectorIndex.getAndIncrement() % tcpSelectors.length];
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
        }

        for (RouplexTcpSelector tcpSelector : tcpSelectors) {
            tcpSelector.requestClose();
        }
    }

// In the future, if needed, we can support this
//    Throttle throttle;
//    public Throttle getTcpClientAcceptThrottle() {
//        return throttle;
//    }
}
