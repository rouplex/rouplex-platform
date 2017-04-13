package org.rouplex.platform.io;

import org.rouplex.commons.annotations.NotNull;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
interface RequestWithAsyncResponseHandler<S, D, E extends Exception> extends RequestWithResponseHandler<S, D> {
    void handleRequest(@NotNull S request, @NotNull ResponseHandler<D, E> responseCallback);
}
