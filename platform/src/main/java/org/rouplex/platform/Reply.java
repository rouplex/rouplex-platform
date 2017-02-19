package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Reply<T> {
    public T reply;
    public long expirationTimestamp;

    public Reply(T reply, long expirationTimestamp) {
        this.reply = reply;
        this.expirationTimestamp = expirationTimestamp;
    }
}
