package org.rouplex.platform.rr;

import org.rouplex.commons.annotations.NotNull;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
interface RequestWithSyncReplyChannel<S, D> extends RequestWithReplyChannel<S, D> {
    @NotNull D send(@NotNull S request);// throws Exception;
}
