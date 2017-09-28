package org.rouplex.platform.tcp;

import org.rouplex.commons.Supplier;
import org.rouplex.commons.annotations.Nullable;
import org.rouplex.nio.channels.SSLSelector;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.Selector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class serves as a binder between the platform and {@link RouplexTcpSelector}s and {@link RouplexTcpClient}s
 * or {@link RouplexTcpServer}s. Internally, it spawns a number of RouplexTcpSelector instances, which will be handling
 * events related to all registered clients and servers.
 *
 * There are two creation patterns for instances of this class:
 *
 * (1) Created via its constructor and passed to one or more {@link RouplexTcpClient}s or {@link RouplexTcpServer}s via
 * {@link RouplexTcpClient.Builder#withRouplexTcpBinder(RouplexTcpBinder)} or
 * {@link RouplexTcpServer.Builder#withRouplexTcpBinder(RouplexTcpBinder)}. In this case, the various clients and/or
 * servers created will not close this instance when they are closed.
 *
 * (2) Created by {@link RouplexTcpClient.Builder} or {@link RouplexTcpServer.Builder} when there is no RouplexTcpBinder
 * assigned to it, in which case a RouplexTcpBinder will be created automatically and used by the endpoint created. In
 * that case, the RouplexTcpBinder will be closed by that endpoint when the endpoint is closed in its turn.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexTcpBinder implements Closeable {
    private final static Supplier<Selector> DEFAULT_SELECTOR_SUPPLIER = new Supplier<Selector>() {
        @Override
        public Selector get() {
            try {
                return SSLSelector.open();
            } catch (IOException ioe) {
                throw new RuntimeException("Could not create SSLSelector", ioe);
            }
        }
    };

    private final static ThreadFactory DEFAULT_THREAD_FACTORY = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }
    };

    private final Object lock = new Object();
    private final ExecutorService executorService;
    private final boolean sharedExecutorService;
    private final RouplexTcpSelector[] tcpSelectors;
    private final AtomicInteger tcpSelectorIndex = new AtomicInteger();
    private boolean closed;

    @Nullable
    protected RouplexTcpClientListener rouplexTcpClientListener;
    @Nullable
    protected RouplexTcpServerListener rouplexTcpServerListener;

    /**
     * Construct an instance using a default selector supplier, a default executor service and default read buffer size.
     */
    public RouplexTcpBinder() {
        this(DEFAULT_SELECTOR_SUPPLIER);
    }

    /**
     * Construct an instance using the specified selector supplier, a default executor service and default read buffer
     * size.
     *
     * @param selectorSupplier
     *          a supplier for selector instances. Expect it to be called once per cpu core available.
     */
    public RouplexTcpBinder(Supplier<Selector> selectorSupplier) {
        this(selectorSupplier, null);
    }

    /**
     * Construct an instance using the specified selector supplier, executor service and a default read buffer size.
     *
     * @param selectorSupplier
     *          a supplier for selector instances. Expect it to be called once per cpu core available.
     * @param executorService
     *          an executor service suggested to have at least as many available threads as there are cpu cores. This
     *          executor will not be shutdown when this instance is closed. On the other hand, if this field is left
     *          null, an executor service will be created and used; when this instance will be closed, that executor
     *          will be shut down.
     */
    public RouplexTcpBinder(Supplier<Selector> selectorSupplier, ExecutorService executorService) {
        this(selectorSupplier, executorService, 1024 * 1024);
    }

    /**
     * Construct an instance using the specified selector supplier, executor service and the read buffer size.
     *
     * @param selectorSupplier
     *          a supplier for selector instances. Expect it to be called once per cpu core available.
     * @param executorService
     *          an executor service suggested to have at least as many available threads as there are cpu cores. This
     *          executor will not be shutdown when this instance is closed. On the other hand, if this field is left
     *          null, an executor service will be created and used; when this instance will be closed, that executor
     *          will be shut down.
     * @param readBufferSize
     *          a positive value indicating how big the read buffer size should be.
     */
    public RouplexTcpBinder(Supplier<Selector> selectorSupplier, ExecutorService executorService, int readBufferSize) {
        tcpSelectors = new RouplexTcpSelector[Runtime.getRuntime().availableProcessors()];

        this.executorService = (sharedExecutorService = executorService != null)
                ? executorService : Executors.newFixedThreadPool(tcpSelectors.length, DEFAULT_THREAD_FACTORY);

        if (readBufferSize <= 0) {
            throw new IllegalArgumentException("Read buffer size must be positive");
        }

        for (int index = 0; index < tcpSelectors.length; index++) {
            tcpSelectors[index] = new RouplexTcpSelector(this, selectorSupplier.get(), readBufferSize);
        }
    }

    /**
     * We assign each new channel to the next {@link RouplexTcpSelector} in a round-robin fashion.
     *
     * @return
     *          the next RouplexTcpSelector to be used
     */
    RouplexTcpSelector nextRouplexTcpSelector() {
        return tcpSelectors[tcpSelectorIndex.getAndIncrement() % tcpSelectors.length];
    }

    ExecutorService getExecutorService() {
        return executorService;
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
            tcpSelector.close();
        }

        if (!sharedExecutorService) {
            executorService.shutdownNow();
        }
    }

    /**
     * Set the listener to be notified on added / removed {@link RouplexTcpClient}s. There is only one such listener,
     * and once set, it cannot be unset or changed.
     *
     * @param rouplexTcpClientListener
     *          the new listener
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
     * Set the listener to be notified on added / removed {@link RouplexTcpServer}s. There is only one such listener,
     * and once set, it cannot be unset or changed.
     *
     * @param rouplexTcpServerListener
     *          the new listener
     */
    public void setRouplexTcpServerListener(@Nullable RouplexTcpServerListener rouplexTcpServerListener) {
        synchronized (lock) {
            if (this.rouplexTcpServerListener != null) {
                throw new IllegalStateException("RouplexTcpServerListener already set.");
            }

            this.rouplexTcpServerListener = rouplexTcpServerListener;
        }
    }

// In the future, if needed, we can support this
//    Throttle throttle;
//    public Throttle getTcpClientAcceptThrottle() {
//        return throttle;
//    }
}
