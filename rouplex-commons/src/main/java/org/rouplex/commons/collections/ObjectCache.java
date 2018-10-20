package org.rouplex.commons.collections;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ObjectCache<T> {
    protected final Object lock = new Object();
    protected final Map<String, Set<T>> valuesByTag = new HashMap<String, Set<T>>();
    protected final SortedMap<Integer, Set<String>> tagsBySize = new TreeMap<Integer, Set<String>>();

    private final int max;
    private final int maxPerTag;
    private int count;

    public ObjectCache(int max, int maxPerTag) {
        this.max = max;
        this.maxPerTag = maxPerTag;
    }

    public T poll(String tag) {
        synchronized (lock) {
            Set<T> values = valuesByTag.get(tag);
            if (values == null) {
                return null;
            }

            int currentSize = values.size();
            Iterator<T> iterator = values.iterator();
            T value = iterator.next();
            iterator.remove();
            if (values.isEmpty()) {
                valuesByTag.remove(tag);
            }

            Set<String> currentSizeGroup = tagsBySize.get(currentSize);
            currentSizeGroup.remove(tag);
            if (currentSizeGroup.isEmpty()) {
                tagsBySize.remove(currentSize);
            }

            if (currentSize > 1) {
                int newSize = currentSize - 1;
                Set<String> newSizeGroup = tagsBySize.get(newSize);
                if (newSizeGroup == null) {
                    tagsBySize.put(newSize, newSizeGroup = new HashSet<String>());
                }
                newSizeGroup.add(tag);
            }

            count--;
            return value;
        }
    }

    public T get(String tag, long timeout, TimeUnit timeUnit) throws Exception {
        long expirationTs = System.currentTimeMillis() + TimeUnit.MICROSECONDS.convert(timeout, timeUnit);

        while (true) {
            T value = poll(tag);
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

    public T get(String key) throws Exception {
        return get(key, Long.MAX_VALUE, TimeUnit.DAYS);
    }

    public boolean offer(String tag, T value) {
        String evictedTag = null;
        T evictedValue = null;
        synchronized (lock) {
            Set<T> values = valuesByTag.get(tag);
            if (values != null && values.size() == maxPerTag) {
                return false;
            }

            if (count == max) {
                int greatestCacheSize = tagsBySize.lastKey();
                if (greatestCacheSize == 1) {
                    // we cannot onEvicted anything
                    return false;
                }

                evictedTag = tagsBySize.get(greatestCacheSize).iterator().next();
                evictedValue = poll(evictedTag);
            }

            if (values == null) {
                valuesByTag.put(tag, values = new HashSet<T>());
            }

            values.add(value);
            int newSize = values.size();
            Set<String> newSizeGroup = tagsBySize.get(newSize);
            if (newSizeGroup == null) {
                tagsBySize.put(newSize, newSizeGroup = new HashSet<String>());
            }
            newSizeGroup.add(tag);

            if (newSize > 1) {
                int previousSize = newSize - 1;
                Set<String> previousSizeGroup = tagsBySize.get(previousSize);
                previousSizeGroup.remove(tag);
                if (previousSizeGroup.isEmpty()) {
                    tagsBySize.remove(previousSize);
                }
            }

            count++;
        }

        // We don't want to fire from within the synchronized block to avoid potential deadlocks. The value has already
        // been removed from cache within the synchronized block, hence could not have been claimed  by another thread.
        if (evictedTag != null) {
            onEvicted(evictedTag, evictedValue);
        }

        return true;
    }


    protected void onEvicted(String tag, T value) {
    }
}
