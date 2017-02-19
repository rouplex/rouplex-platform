package org.rouplex.platform.rr;

import org.rouplex.platform.RouplexService;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface AsyncRepliesService<S, D> extends RouplexService {
    void serviceRequest(RequestWithAsyncReplies<S, D> requestWithAsyncReplies);
}
