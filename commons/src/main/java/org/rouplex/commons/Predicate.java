package org.rouplex.commons;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface Predicate<T> {
    boolean test(T value);
}
