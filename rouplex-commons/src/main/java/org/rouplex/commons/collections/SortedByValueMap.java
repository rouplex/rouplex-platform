package org.rouplex.commons.collections;

import org.rouplex.commons.annotations.NotThreadSafe;
import org.rouplex.commons.concurrency.Synchronized;

import java.util.*;

/**
 * A map like structure (which can be made to implement Map) providing an iterator over its entries sorted by value.
 * This implementation is not thread safe. Please check {@link Synchronized} for a thread safe wrapper/implementation.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@NotThreadSafe
public class SortedByValueMap<K, V> {
    private final Map<K, V> keyValue = new HashMap<K, V>();
    private final SortedMap<V, Set<K>> orderedByValue = new TreeMap<V, Set<K>>();

    /**
     * Put a new entry.
     *
     * @param key
     *          the key
     * @param value
     *          the value
     * @return
     *          the previous value associated to this key, or null if none
     */
    public V put(K key, V value) {
        V oldValue = removeOldValue(key, keyValue.put(key, value));

        Set<K> keysForValue = orderedByValue.get(value);
        if (keysForValue == null) {
            orderedByValue.put(value, keysForValue = new HashSet<K>());
        }
        keysForValue.add(key);

        return oldValue;
    }

    /**
     * Get the iterable over the entries of this instance, sorted by the ascending order of their values.
     *
     * @return
     *          the iterable over the entries
     */
    public Iterable<Map.Entry<K, V>> sortedByValue() {
        return new Iterable<Map.Entry<K, V>>() {
            @Override
            public Iterator<Map.Entry<K, V>> iterator() {
                return new Iterator<Map.Entry<K, V>>() {
                    int remaining = keyValue.size();
                    final Iterator<Map.Entry<V, Set<K>>> entries = orderedByValue.entrySet().iterator();
                    Map.Entry<V, Set<K>> currentEntry;
                    Iterator<K> keys;
                    Map.Entry<K, V> current;

                    {
                        if (entries.hasNext()) {
                            currentEntry = entries.next();
                            keys = currentEntry.getValue().iterator();
                        }
                    }

                    @Override
                    public boolean hasNext() {
                        return remaining > 0;
                    }

                    @Override
                    public Map.Entry<K, V> next() {
                        if (remaining == 0) {
                            throw new NoSuchElementException();
                        }

                        while (!keys.hasNext()) {
                            currentEntry = entries.hasNext() ? entries.next() : null;
                            if (currentEntry == null) {
                                throw new ConcurrentModificationException();
                            }

                            keys = currentEntry.getValue().iterator();
                        }

                        remaining--;
                        return current = new MapEntry<K, V>(keys.next(), currentEntry.getKey());
                    }

                    @Override
                    public void remove() {
                        if (current == null) {
                            throw new IllegalStateException();
                        }

                        keys.remove();

                        if (currentEntry.getValue().isEmpty()) {
                            entries.remove();
                        }

                        keyValue.remove(current.getKey());
                    }
                };
            }
        };
    }

    private class MapEntry<K, V> implements Map.Entry<K, V> {
        private final K key;
        private final V value;

        MapEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            throw new RuntimeException("setValue not supported");
        }
    }

    private V removeOldValue(K key, V oldValue) {
        if (oldValue != null) {
            Set<K> keys = orderedByValue.get(oldValue);
            keys.remove(key);
            if (keys.isEmpty()) {
                orderedByValue.remove(oldValue);
            }
        }

        return oldValue;
    }
}
