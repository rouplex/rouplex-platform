package org.rouplex.platform;

import org.rouplex.commons.annotations.NotNull;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RequestWithSyncReplyHandler<S, D> extends RequestHandler<S, D> {
    @NotNull D handleRequest(@NotNull S request);
}
