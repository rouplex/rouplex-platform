package org.rouplex.platform.rr;

/**
 * A channel which can receive payloads of a generic type
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ReceiveChannel<T> {
    /**
     * Receive a payload (to process)
     *
     * @param payload
     *          Generic payload to be received
     * @return
     *          True if the payload was handled completely
     */
    boolean receive(T payload);
}
