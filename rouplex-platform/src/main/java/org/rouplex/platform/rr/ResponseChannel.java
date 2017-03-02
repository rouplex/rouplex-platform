package org.rouplex.platform.rr;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ResponseChannel<R, E extends Exception> {
    void onResponse(R response);
    void onException(E exception);
}
