package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RequestWithAsyncReplyHandler<S, D> extends RequestHandler<S, D> {
    boolean handleRequest(RequestWithAsyncReply<S, D> requestWithAsyncReply);
}
