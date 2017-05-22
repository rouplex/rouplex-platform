package org.rouplex.platform.tcp;

import org.rouplex.commons.Supplier;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSelector;
import org.rouplex.platform.io.Throttle;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.Selector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpBinder implements Closeable {
    protected final static Supplier<Selector> DEFAULT_SELECTOR_SUPPLIER = new Supplier<Selector>() {
        @Override
        public Selector get() {
            try {
                return SSLSelector.open();
            } catch (IOException ioe) {
                throw new RuntimeException("Could not create SSLSelector", ioe);
            }
        }
    };

    private static final ThreadFactory DEFAULT_THREAD_FATORY = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }
    };

    protected final Object lock = new Object();
    protected final ExecutorService executorService;
    protected final boolean sharedExecutorService;
    private final RouplexTcpSelector[] tcpSelectors;
    private final AtomicInteger tcpSelectorIndex = new AtomicInteger();
    private boolean closed;

    @Nullable
    protected RouplexTcpClientListener rouplexTcpClientListener;
    @Nullable
    protected RouplexTcpServerListener rouplexTcpServerListener;

    public RouplexTcpBinder() {
        this(DEFAULT_SELECTOR_SUPPLIER);
    }

    public RouplexTcpBinder(Supplier<Selector> selectorSupplier) {
        this(selectorSupplier, null);
    }

    public RouplexTcpBinder(Supplier<Selector> selectorSupplier, ExecutorService executorService) {
        this(selectorSupplier, executorService, 1024 * 1024);
    }

    public RouplexTcpBinder(Supplier<Selector> selectorSupplier, ExecutorService executorService, int readBufferSize) {
        tcpSelectors = new RouplexTcpSelector[Runtime.getRuntime().availableProcessors()];

        this.executorService = (sharedExecutorService = executorService != null)
                ? executorService : Executors.newFixedThreadPool(tcpSelectors.length, DEFAULT_THREAD_FATORY);

        for (int counter = 0; counter < tcpSelectors.length; counter++) {
            tcpSelectors[counter] = new RouplexTcpSelector(this, selectorSupplier.get(), readBufferSize);
        }
    }

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

        for (int counter = 0; counter < tcpSelectors.length; counter++) {
            tcpSelectors[counter].close();
        }

        if (!sharedExecutorService) {
            executorService.shutdownNow();
        }
    }

    // In the future, if needed, we can support this
    Throttle throttle;
    public Throttle getTcpClientAcceptThrottle() {
        return throttle;
    }

    /**
     * Whoever wants to know about added / removed {@link RouplexTcpClient}s
     *
     * @param rouplexTcpClientListener
     */
    public void setRouplexTcpClientListener(@Nullable RouplexTcpClientListener rouplexTcpClientListener) {
        synchronized (lock) {
            if (this.rouplexTcpClientListener != null) {
                throw new IllegalStateException("RouplexTcpClientListener already set.");
            }

            this.rouplexTcpClientListener = rouplexTcpClientListener;
        }
    }

    /**
     * Whoever wants to know about added / removed {@link RouplexTcpServer}s
     *
     * @param rouplexTcpServerListener
     */
    public void setRouplexTcpServerListener(@Nullable RouplexTcpServerListener rouplexTcpServerListener) {
        synchronized (lock) {
            if (this.rouplexTcpServerListener != null) {
                throw new IllegalStateException("RouplexTcpServerListener already set.");
            }

            this.rouplexTcpServerListener = rouplexTcpServerListener;
        }
    }
}
