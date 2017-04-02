package org.rouplex.platform.io;

import org.rouplex.commons.annotations.NotNull;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
interface RequestWithAsyncResponseChannel<S, D, E extends Exception> extends RequestWithResponseChannel<S, D> {
    void send(@NotNull S request, @NotNull ResponseChannel<D, E> replyCallback);
}
