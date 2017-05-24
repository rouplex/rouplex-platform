package org.rouplex.commons.collections;

import org.rouplex.commons.Predicate;
import org.rouplex.commons.annotations.NotThreadSafe;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A convenience class providing functionality which is actually present since java-1.8, needed since rouplex targets
 * java-1.6
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@NotThreadSafe
public abstract class AbstractIterator<T> implements Iterator<T> {

    final Predicate<T> predicate;
    protected T next;

    public AbstractIterator(Predicate<T> predicate) {
        this.predicate = predicate;
    }

    protected abstract void locateNext();

    protected void locateNextFiltered() {
        do {
            locateNext();
        } while (next != null && predicate != null && !predicate.test(next));
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public T next() {
        T result = next;
        if (result == null) {
            throw new NoSuchElementException();
        }

        locateNextFiltered();
        return result;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove");
    }
}
