package org.rouplex.platform.io;

import java.io.IOException;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ReactiveChannel {
    /**
     * The number of milliseconds that the channel is allowed to block performing an operation. This value can be
     * changed at any moment, and any pending read/write operations must honor the new value immediately.
     *
     * A truly reactive channel keeps the default value of -1, and sets up a callback to which it reacts by reading or
     * writing in a non blocking manner from the channel.
     *
     * @param timeoutMillis
     *          -1: (default) operation is non-blocking, the channel will read or write as many bytes possible
     *              without blocking before returning.
     *           0: indefinite timeout. In case of a read, the channel will only return after reading at least one
     *              byte (but could be returning as many bytes available in destination buffer). In case of a write
     *              the channel will only return after writing all the bytes available in source buffer.
     *          >0: definite timeout. In case of a read, the channel will only return after reading at least one
     *              byte (but could be returning as many bytes available in destination buffer), or after
     *              timeoutMillis, whichever comes first. In case of a write, the channel will return after writing
     *              all bytes available in source buffer, or after timeoutMillis, whichever comes first.
     */
    void setTimeout(int timeoutMillis);

    /**
     * Add a callback to be notified whenever the channel is ready to perform its default operation. Once the callback
     * has been called, then another call to this same method is necessary to get notified again on channel readiness.
     *
     * @param channelReadyCallback
     *          The {@link Runnable} to be called when the channel is ready to perform its default operation
     * @throws IOException
     *          If such operation is not possible
     */
    void addChannelReadyCallback(Runnable channelReadyCallback) throws IOException;
}
