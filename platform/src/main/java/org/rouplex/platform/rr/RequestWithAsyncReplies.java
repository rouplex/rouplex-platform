package org.rouplex.platform.rr;

import org.rouplex.commons.annotations.Nullable;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public abstract class RequestWithAsyncReplies<S, D> extends Request<S> {
    public RequestWithAsyncReplies(S request, long expirationTimestamp) {
        super(request, expirationTimestamp);
    }

    /**
     * A null reply will be considered as the end of the replies and will not be conveyed to the receiving entity.
     * The upper layers must handle the semantics of when the stream of replies ends. This way we don't tarnish this
     * layer with wire formats, keeping it open for byte level operations such as video and other RT protocols.
     *
     * @param reply
     * @return true if the request is still valid, false otherwise (which means the reply won't reach the destination)
     */
    public abstract boolean addReply(@Nullable D reply);
}
