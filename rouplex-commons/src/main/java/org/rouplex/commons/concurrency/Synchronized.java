package org.rouplex.commons.concurrency;

import org.rouplex.commons.annotations.ThreadSafe;
import org.rouplex.commons.collections.SortedByValueMap;

/**
 * This class will contain various helpers related to thread safety and synchronization.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Synchronized {

    /**
     * Return a synchronized (thread safe) instance of {@link SortedByValueMap} by first acquiring a shared lock, then
     * forwarding all the calls to the original instance.
     *
     * @param source
     * @param <K>
     * @param <V>
     * @return
     */
    public static <K, V> SortedByValueMap<K, V> getSynchronized(final SortedByValueMap<K, V> source) {
        return new SortedByValueMap<K, V>() {
            Object lock = new Object();

            @ThreadSafe
            @Override
            public V put(K key, V value) {
                synchronized (lock) {
                    return source.put(key, value);
                }
            }
        };
    }
}
