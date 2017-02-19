package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RequestWithAsyncReplyHandler<S, D> extends RequestHandler<S, D> {
    void handleRequest(RequestWithAsyncReply<S, D> requestWithAsyncReply);
}
