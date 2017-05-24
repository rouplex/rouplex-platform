package org.rouplex.platform.io;

/**
 * A receiver which can receive payloads of a generic type. This interface makes no assumptions about the payload
 * structure or size, whether it can be null or not, whether the callee is supposed to handle it promptly or not; it is
 * up to the implementing classes to define those semantics.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface Receiver<T> {
    /**
     * Receive a payload of a generic type.
     *
     * @param payload
     *          generic payload to be received
     * @return
     *          true if the payload was handled
     */
    boolean receive(T payload);
}
