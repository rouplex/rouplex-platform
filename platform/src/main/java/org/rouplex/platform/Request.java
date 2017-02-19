package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Request<T> {
    public final T request;
    public final long expirationTimestamp;
    public int cancelCode;

    public Request(T request, long expirationTimestamp) {
        this.request = request;
        this.expirationTimestamp = expirationTimestamp;
    }

    public void cancel(int code) {
        cancelCode = code;
    }
}
