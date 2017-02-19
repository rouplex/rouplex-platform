package org.rouplex.platform.rr;

import org.rouplex.platform.RouplexService;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface AsyncReplyService<S, D> extends RouplexService {
    void serviceRequest(RequestWithAsyncReply<S, D> requestWithAsyncReply);
}
