package org.rouplex.platform.rr;

import org.rouplex.commons.annotations.NotNull;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
interface RequestWithAsyncReplyChannel<S, D, E extends Exception> extends RequestWithReplyChannel<S, D> {
    void send(@NotNull S request, @NotNull ReplyChannel<D, E> replyCallback);
}
