package org.rouplex.commons.collections;

import org.rouplex.commons.annotations.NotThreadSafe;

import java.util.*;

/**
 * A map like structure (which can be made to implement Map) providing an iterator over its entries sorted by value.
 * This implementation is not thread safe. Please check {@link org.rouplex.commons.concurrency.Synchronized}
 * synchronized (sine in the common use case we expect the synchronization to
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@NotThreadSafe
public class SortedByValueMap<K, V> {
    private final Map<K, V> keyValue = new HashMap<K, V>();
    private final SortedMap<V, Set<K>> orderedByValue = new TreeMap<V, Set<K>>();

    public V put(K key, V value) {
        V oldValue = removeOldValue(key, keyValue.put(key, value));

        Set<K> keysForValue = orderedByValue.get(value);
        if (keysForValue == null) {
            orderedByValue.put(value, keysForValue = new HashSet<K>());
        }
        keysForValue.add(key);

        return oldValue;
    }

    public V remove(K key) {
        return removeOldValue(key, keyValue.remove(key));
    }

    public Iterable<Map.Entry<K, V>> sortedByValue() {
        return new Iterable<Map.Entry<K, V>>() {
            @Override
            public Iterator<Map.Entry<K, V>> iterator() {
                return new Iterator<Map.Entry<K, V>>() {
                    final Iterator<Map.Entry<V, Set<K>>> entries = orderedByValue.entrySet().iterator();
                    Map.Entry<V, Set<K>> currentEntry;
                    Iterator<K> keys;
                    Map.Entry<K, V> current;
                    Map.Entry<K, V> next;

                    {
                        if (entries.hasNext()) {
                            currentEntry = entries.next();
                            keys = currentEntry.getValue().iterator();
                            locateNext();
                        }
                    }

                    void locateNext() {
                        while (!keys.hasNext()) {
                            currentEntry = entries.hasNext() ? entries.next() : null;
                            if (currentEntry == null) {
                                next = null;
                                return;
                            }

                            keys = currentEntry.getValue().iterator();
                        }

                        next = new MapEntry<K, V>(keys.next(), currentEntry.getKey());
                    }

                    @Override
                    public boolean hasNext() {
                        return next != null;
                    }

                    @Override
                    public Map.Entry<K, V> next() {
                        if (next == null) {
                            throw new NoSuchElementException();
                        }

                        current = next;
                        locateNext();
                        return current;
                    }

                    @Override
                    public void remove() {
                        if (current == null) {
                            throw new IllegalStateException();
                        }

                        SortedByValueMap.this.remove(current.getKey());
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
