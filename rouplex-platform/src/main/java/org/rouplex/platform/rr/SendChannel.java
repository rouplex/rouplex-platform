package org.rouplex.platform.rr;

/**
 * A channel which can send payloads of a generic type.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface SendChannel<T> {
    /**
     * Send the payload
     *
     * @param payload
     *          The payload
     * @return
     *          True if the payload was sent fully
     */
    boolean send(T payload);
}
