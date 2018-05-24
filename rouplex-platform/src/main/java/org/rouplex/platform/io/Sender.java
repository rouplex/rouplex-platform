package org.rouplex.platform.io;

import java.io.IOException;

/**
 * A sender which can send payloads of a generic type. This interface makes no assumptions about the payload
 * structure or size, whether it can be null or not, whether the callee is supposed to handle it promptly or not; it is
 * up to the implementing classes to define those semantics.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
interface Sender<T> {
    /**
     * Send the payload of a generic type.
     *
     * @param payload
     *          the payload to be sent
     * @throws IOException
     *          if this sender cannot carry the send task
     */
    void send(T payload) throws IOException;
}
