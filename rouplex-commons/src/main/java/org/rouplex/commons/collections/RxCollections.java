package org.rouplex.commons.collections;

import java.util.*;

/**
 * A handful of methods providing useful functionality related to collections.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RxCollections {
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

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
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

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
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
}