package org.rouplex.common.collections;

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

    /**
     * Create a collection and copy the content of the iterator to it before returning it.
     *
     * @param iterator an {@link Iterator} over elements of a type T. It will be consumed entirely before returning from the call
     * @param <T> any type T
     * @return a new collection containing elements retrieved from the iterator
     */
    public static <T> Collection<T> getCollection(Iterator<T> iterator) {
        return appendToCollection(iterator, new ArrayList<T>());
    }

    /**
     * Consume and append the content of the iterator into the collection.
     * This can be helpful since most collections don't take in an iterator in their constructors.
     *
     * @param iterator an {@link Iterator} over elements of a type T. It will be consumed entirely before returning from the call
     * @param collection a collection such as a {@link List}, {@link Set}, {@link SortedSet} etc
     * @param <T> any type T. Forces the type of iterator elements to be the same as the one of the collection elements
     * @return the content of the updated collection
     */
    public static <T> Collection<T> appendToCollection(Iterator<T> iterator, Collection<T> collection) {
        for (T obj : getIterable(iterator)) {
            collection.add(obj);
        }

        return collection;
    }
}
