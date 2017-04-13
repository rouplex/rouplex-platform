package org.rouplex.platform.io;

import org.rouplex.commons.annotations.NotNull;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
interface RequestWithSyncResponseHandler<S, D> extends RequestWithResponseHandler<S, D> {
    @NotNull D handleRequest(@NotNull S request) throws Exception;
}
