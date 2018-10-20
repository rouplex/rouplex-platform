package org.rouplex.commons;

/**
 * A useful construct copied from java8 for use with older jdks such as 1.6
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ThrowingSupplier<T> {
    T get() throws Exception;
}
