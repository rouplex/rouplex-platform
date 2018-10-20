package org.rouplex.commons.collections;

import org.rouplex.commons.ThrowingSupplier;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ObjectPool<T> {
    protected final Object lock = new Object();
    protected final Queue<T> available = new LinkedList<T>();
    protected final Set<T> used = new HashSet<T>();
    private final ThrowingSupplier<T> supplier;
    private final int min;
    private final int max;
    private int count;

    public ObjectPool(ThrowingSupplier<T> supplier, int min, int max) {
        this.supplier = supplier;
        this.min = min;
        this.max = max;
    }

    public T poll() throws Exception {
        synchronized (lock) {
            T result = available.poll();
            if (result != null || count == max) {
                return result;
            }

            count++;
        }

        // this can be a costly operation, and it is safe to perform it outside the locked section for minimal latency
        T value = supplier.get();

        synchronized (lock) {
            used.add(value);
        }
        return value;
    }

    public T get(long timeout, TimeUnit timeUnit) throws Exception {
        long expirationTs = System.currentTimeMillis() + TimeUnit.MICROSECONDS.convert(timeout, timeUnit);

        while (true) {
            T value = poll();
            if (value != null) {
                return value;
            }

            long timeRemaining = expirationTs - System.currentTimeMillis();
            if (timeRemaining <= 0) {
                throw new IllegalStateException("Timeout");
            }

            synchronized (lock) {
                try {
                    lock.wait(timeRemaining);
                } catch (InterruptedException ie) {
                    throw new IllegalStateException("Interrupted");
                }
            }
        }
    }

    public T get() throws Exception {
        return get(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    public boolean release(T object) {
        synchronized (lock) {
            if (available.size() < min) {
                available.add(object);
                return true;
            }

            used.remove(object);
            count--;
        }

        return false;
    }
}
