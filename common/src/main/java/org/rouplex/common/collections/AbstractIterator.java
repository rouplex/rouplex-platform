package org.rouplex.common.collections;

import java.util.Iterator;

/**
 * A convenience class providing functionality which is actually present since java-1.8,
 * needed since rouplex targets java-1.6
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public abstract class AbstractIterator<T> implements Iterator<T> {

    private static final Iterator emptyIterator = new AbstractIterator() {
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

    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove");
    }
}
