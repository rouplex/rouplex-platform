package org.rouplex.platform.rr;

import org.rouplex.commons.annotations.NotNull;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public abstract class RequestWithAsyncReply<S, D>  extends Request<S> {
    public RequestWithAsyncReply(S request, long expirationTimestamp) {
        super(request, expirationTimestamp);
    }

    /**
     * @param reply
     * @return true if the request is still valid, false otherwise (which means the reply won't reach the destination)
     */
    public abstract boolean setReply(@NotNull D reply);
}
