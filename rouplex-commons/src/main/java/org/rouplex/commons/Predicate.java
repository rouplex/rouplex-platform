package org.rouplex.commons;

/**
 * Some useful, functional programing functionality.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface Predicate<T> {
    boolean test(T value);
}
