package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RequestWithMultipleAsyncRepliesHandler<S, D> extends RequestHandler<S, D> {
    boolean handleRequest(RequestWithMultipleAsyncReplies<S, D> requestWithMultipleAsyncReplies);
}