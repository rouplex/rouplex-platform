package org.rouplex.platform.rr;

import java.io.IOException;

/**
 * A channel which can send payloads of a generic type.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface SendChannel<T> {
    /**
     * Send the payload. This is a non blocking call, and the payload type T must provide a way to expose the amount
     * of the data sent.
     *
     * @param payload
     *          The payload to be sent.
     * @throws IOException
     *          If this channel is closed (either by a previous call {@link #send(Object)} with null as parameter or by
     *          an underlying network problem which had (or just) caused this channel to close)
     */
    void send(T payload) throws IOException;
}
