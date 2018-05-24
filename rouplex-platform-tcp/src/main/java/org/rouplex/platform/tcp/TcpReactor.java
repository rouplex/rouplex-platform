package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The starting place for configuring and creating tcp endpoints, an instance of this class provides the builders for
 * {@link TcpClient} and {@link TcpServer} instances.
 *
 * Multiple {@link TcpSelector} instances are used internally, each performing channel selections on a subset of the
 * endpoints for maximum use of processing resources. The suggested usage is to create just one instance of this class,
 * then subsequently create all tcp clients or servers starting from there. Though not necessary, more than one instance
 * of this class can be created, for example if you must use more than one {@link SelectorProvider}.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpReactor implements Closeable {
    private final TcpSelector[] tcpSelectors;
    final Set<Thread> tcpSelectorThreads = new HashSet<Thread>();
    private final AtomicInteger tcpSelectorIndex = new AtomicInteger();
    public final TcpMetrics tcpMetrics = new TcpMetrics();

    private boolean closed;
    private Exception fatalException;

    public TcpReactor() throws IOException {
        this(SelectorProvider.provider());
    }

    public TcpReactor(SelectorProvider selectorProvider) throws IOException {
        this(selectorProvider, Runtime.getRuntime().availableProcessors(), Executors.defaultThreadFactory());
    }

    /**
     * Construct an instance using the specified {@link SelectorProvider}, {@link ThreadFactory} and read buffer size.
     *
     * @param selectorProvider
     *          The provider to be used to create the {@link Selector} instances. Expect it to be called once per cpu
     *          core available.
     * @throws IOException
     *          If the instance could not be created, due to to a problem creating the selector or similar
     */
    public TcpReactor(SelectorProvider selectorProvider, int threadCount, ThreadFactory threadFactory) throws IOException {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("ThreadCount must be a positive value");
        }

        tcpSelectors = new TcpSelector[threadCount];
        for (int index = 0; index < tcpSelectors.length; index++) {
            TcpSelector tcpSelector = new TcpSelector(this, selectorProvider.openSelector(),
                threadFactory, "TcpReactor-" + hashCode() + "-TcpSelector-" + index);

            tcpSelectorThreads.add(tcpSelector.tcpSelectorThread);
            tcpSelectors[index] = tcpSelector;
        }
    }

    /**
     * Create a new builder to be used to build a TcpClient.
     *
     * @return
     *          The new tcp client builder
     */
    public TcpClient.Builder newTcpClientBuilder() throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException(
                    "TcpReactor is closed and cannot create a TcpClientBuilder", fatalException);
            }

            return new TcpClient.Builder(this);
        }
    }

    /**
     * Create a new builder to be used to build a TcpServer.
     *
     * @return
     *          The new tcp server builder
     */
    public TcpServer.Builder newTcpServerBuilder() throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException(
                    "TcpReactor is closed and cannot create a TcpServerBuilder", fatalException);
            }

            return new TcpServer.Builder(this);
        }
    }

    /**
     * We assign each created channel to the next {@link TcpSelector} in a round-robin fashion.
     * TODO: Next TcpSelector should be the one with less SelectionKeys in it
     *
     * @return
     *          The next TcpSelector to be used
     */
    TcpSelector nextTcpSelector() {
        return tcpSelectors[tcpSelectorIndex.getAndIncrement() % tcpSelectors.length];
    }

    void close(@Nullable Exception optionalException) {
        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
            fatalException = optionalException;
        }

        for (TcpSelector tcpSelector : tcpSelectors) {
            tcpSelector.requestClose(optionalException);
        }
    }

    @Override
    public void close() {
        close(null);
    }
}
