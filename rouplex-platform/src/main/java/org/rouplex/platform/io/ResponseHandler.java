package org.rouplex.platform.io;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ResponseHandler<R, E extends Exception> {
    void handleResponse(R response);
    void handleException(E exception);
}
