package org.rouplex.platform.rr;

import org.rouplex.commons.annotations.NotNull;
import org.rouplex.platform.RouplexService;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface SyncReplyService<S, D> extends RouplexService {
    @NotNull D serviceRequest(@NotNull S request);
}
