package org.rouplex.commons.collections;

import java.util.*;

/**
 * A handful of methods providing useful functionality related to collections.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexCollections {
    public static <T> Iterable<T> getIterable(final Iterator<T> iterator) {
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                return iterator;
            }
        };
    }

    private static final Iterator emptyIterator = new Iterator() {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Object next() {
            return null;
        }
    };

    public static <T> Iterator<T> getEmptyIterator() {
        return emptyIterator;
    }

    public static <E> Iterator<E> singletonIterator(final E element) {
        return new Iterator<E>() {
            boolean hasNext = true;

            @Override
            public boolean hasNext() {
                return hasNext;
            }

            @Override
            public E next() {
                hasNext = false;
                return element;
            }
        };
    }

    /**
     * Create a collection and copy the content of the iterator to it before returning it.
     *
     * @param iterator
     *          an {@link Iterator} over elements of a type T. It will be consumed entirely before returning.
     * @param <T>
     *          any type T
     * @return
     *          a new collection containing elements retrieved from the iterator
     */
    public static <T> Collection<T> getCollection(Iterator<T> iterator) {
        return appendToCollection(iterator, new ArrayList<T>());
    }

    /**
     * Consume and append the content of the iterator into the collection. This can be helpful since most collections
     * don't take in an iterator in their constructors.
     *
     * @param iterator
     *          an {@link Iterator} over elements of a type T. It will be consumed entirely before returning.
     * @param collection
     *          a collection such as a {@link List}, {@link Set}, {@link SortedSet} etc
     * @param <T>
     *          any type T. Forces the type of iterator elements to be the same as the one of the collection elements.
     * @return the content of the updated collection
     */
    public static <T> Collection<T> appendToCollection(Iterator<T> iterator, Collection<T> collection) {
        for (T obj : getIterable(iterator)) {
            collection.add(obj);
        }

        return collection;
    }

    public static <T> Set<T> ungrowableSet(final Set<? extends T> inner) {
        return new Set<T>() {
            @Override
            public int size() {
                return inner.size();
            }

            @Override
            public boolean isEmpty() {
                return inner.isEmpty();
            }

            @Override
            public boolean contains(Object element) {
                return inner.contains(element);
            }

            @Override
            public Object[] toArray() {
                return inner.toArray();
            }

            @Override
            public <T1> T1[] toArray(T1[] array) {
                return inner.toArray(array);
            }

            @Override
            public String toString() {
                return inner.toString();
            }

            @Override
            public Iterator<T> iterator() {
                return (Iterator<T>) inner.iterator();
            }

            @Override
            public boolean equals(Object other) {
                return inner.equals(other);
            }

            @Override
            public int hashCode() {
                return inner.hashCode();
            }

            @Override
            public void clear() {
                inner.clear();
            }

            @Override
            public boolean remove(Object element) {
                return inner.remove(element);
            }

            @Override
            public boolean containsAll(Collection<?> collection) {
                return inner.containsAll(collection);
            }

            @Override
            public boolean removeAll(Collection<?> collection) {
                return inner.removeAll(collection);
            }

            @Override
            public boolean retainAll(Collection<?> collection) {
                return inner.retainAll(collection);
            }

            @Override
            public boolean add(T element) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean addAll(Collection<? extends T> collection) {
                throw new UnsupportedOperationException();
            }
        };
    }
}