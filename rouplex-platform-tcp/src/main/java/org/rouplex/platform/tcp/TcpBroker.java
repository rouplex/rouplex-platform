package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The starting place for configuring and creating tcp endpoints, an instance of this class provides the builders for
 * {@link TcpClient} and {@link TcpServer} instances.
 *
 * Multiple {@link TcpSelector} are used internally, each performing channel selections on a subset of the
 * endpoints for maximum use of processing resources. The suggested usage is to create just one instance of this class,
 * then subsequently create all tcp clients or servers starting from that instance. Though not necessary, more than one
 * instance of this class can be created, for example if you must use more than one {@link SelectorProvider}.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpBroker implements Closeable {
    private final TcpSelector[] tcpSelectors;
    private final AtomicInteger tcpSelectorIndex = new AtomicInteger();
    public final TcpMetrics tcpMetrics = new TcpMetrics();

    private boolean closed;
    private Exception fatalException;

    public TcpBroker() throws IOException {
        this(SelectorProvider.provider());
    }

    /**
     * Construct an instance using the specified {@link SelectorProvider}, {@link ThreadFactory} and read buffer size.
     *
     * @param selectorProvider
     *          the provider to be used to create the {@link Selector} instances. Expect it to be called once per cpu
     *          core available.
     * @throws IOException
     *          if the instance could not be created, due to to a problem creating the selector or similar
     */
    public TcpBroker(SelectorProvider selectorProvider) throws IOException {
        tcpSelectors = new TcpSelector[Runtime.getRuntime().availableProcessors()];
        for (int index = 0; index < tcpSelectors.length; index++) {
            tcpSelectors[index] = new TcpSelector(this, selectorProvider.openSelector());
            Thread thread = new Thread(tcpSelectors[index]);
            thread.setDaemon(true);
            thread.setName("TcpBroker-" + hashCode() + "-" + tcpSelectorIndex.incrementAndGet());
            thread.start();
        }
    }

    /**
     * Create a new builder to be used to build a TcpClient.
     *
     * @return  the new tcp client builder
     */
    public TcpClient.Builder newTcpClientBuilder() throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException(
                    "TcpBroker is closed and cannot create Builder", fatalException);
            }

            return new TcpClient.Builder(this);
        }
    }

    /**
     * Create a new builder to be used to build a TcpServer.
     *
     * @return
     *          the new tcp server builder
     */
    public TcpServer.Builder newTcpServerBuilder() throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException(
                    "TcpBroker is closed and cannot create TcpServerBuilder", fatalException);
            }

            return new TcpServer.Builder(this);
        }
    }

    /**
     * We assign each created channel to the next {@link TcpSelector} in a round-robin fashion.
     *
     * @return
     *          the next TcpSelector to be used
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

// In the future, if needed, we can support this
//    Throttle throttle;
//    public Throttle getTcpClientAcceptThrottle() {
//        return throttle;
//    }
}
