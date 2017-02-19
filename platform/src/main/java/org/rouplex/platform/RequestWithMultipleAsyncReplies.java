package org.rouplex.platform;

import org.rouplex.commons.annotations.Nullable;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public abstract class RequestWithMultipleAsyncReplies<S, D> extends Request<S> {
    public RequestWithMultipleAsyncReplies(S request, long expirationTimestamp) {
        super(request, expirationTimestamp);
    }

    /**
     * A null reply will be considered as the end of the replies and will be conveyed to the receiving entity as null
     *
     * @param reply
     * @return true if the request is still valid, false otherwise (which means the reply won't reach the destination)
     */
    public abstract boolean addReply(@Nullable D reply);
}
