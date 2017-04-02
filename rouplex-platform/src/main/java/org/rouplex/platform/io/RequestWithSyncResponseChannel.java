package org.rouplex.platform.io;

import org.rouplex.commons.annotations.NotNull;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
interface RequestWithSyncResponseChannel<S, D> extends RequestWithResponseChannel<S, D> {
    @NotNull D send(@NotNull S request);// throws Exception;
}
