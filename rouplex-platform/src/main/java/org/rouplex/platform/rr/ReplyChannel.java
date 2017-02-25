package org.rouplex.platform.rr;

/**
 * Experimental
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ReplyChannel<R, E extends Exception> {
    void onReply(R reply);
    void onException(E exception);
}
