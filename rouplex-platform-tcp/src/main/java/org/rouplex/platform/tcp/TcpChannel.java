package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A channel providing generic read/write functionality over Tcp.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class TcpChannel {
    protected final Object lock = new Object();
    protected final TcpClient tcpClient;
    protected final SocketChannel socketChannel;

    // Tandem fields to keep Garbage Collection at minimum
    @GuardedBy("lock") protected Runnable channelReadyCallback;
    @GuardedBy("lock") protected List<Runnable> channelReadyCallbacks;

    @GuardedBy("lock") protected ByteBuffer currentByteBuffer;

    protected int timeoutMillis = -1;
    protected boolean channelReady;

    protected final Runnable notifyAllCallback = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    };

    TcpChannel(TcpClient tcpClient) {
        this.tcpClient = tcpClient;
        this.socketChannel = (SocketChannel) tcpClient.getSelectableChannel();
    }

    public TcpClient getTcpClient() {
        return tcpClient;
    }

    /**
     * The number of milliseconds that the channel is allowed to block performing an operation. Once the
     * {@link TcpReadChannel} is built, this value cannot be altered.
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
     * @return the builder for chaining other settings or build the TcpClient.
     */
    public void setTimeout(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis == 0 ? Integer.MAX_VALUE : timeoutMillis;
    }

    /**
     * Set a maximum rate this channel is allowed to reach. By default, this value is -1, meaning there is no rate
     * limit.
     *
     * The rate is expressed in "bytes per duration of timeUnit".
     *
     * As an example:
     *      maxRate=100 (bytes), duration=1, timeUnit=TimeUnit.MILLIS is different from
     *      maxRate=1000 (bytes), duration=10, timeUnit=TimeUnit.MILLIS since the former one is calculated every
     *      millisecond and the later is calculated every 10 milliseconds (allowing for occasional bursts up,
     *      as long as there are no more than 1000 bytes during the 10 milliseconds)
     *
     * @param maxRate
     *          The maximum rate in number of bytes.
     * @param duration
     *          The duration for which the maxRate has to be capped.
     * @param timeUnit
     *          The time unit related to the duration parameter.
     */
    public void setMaxRate(long maxRate, long duration, TimeUnit timeUnit) {
        throw new UnsupportedOperationException("setMaxRate() is not supported yet by this channel");
    }

    public void addChannelReadyCallback(Runnable channelReadyCallback) throws IOException {
        synchronized (lock) {
            if (this.channelReadyCallback == null) {
                this.channelReadyCallback = channelReadyCallback;

                if (Thread.currentThread() != tcpClient.brokerThread) {
                    tcpClient.tcpSelector.asyncUpdateTcpClient(tcpClient);
                }
            } else {
                if (channelReadyCallbacks == null) {
                    channelReadyCallbacks = new ArrayList<>();
                }

                channelReadyCallbacks.add(channelReadyCallback);
            }
        }
    }

    /**
     * Get notification from selector that this channel can perform the op, and forward such notification to
     * eventual listeners.
     */
    void handleChannelReady() {
        channelReady = true;

        Runnable channelReadyCallback;
        List<Runnable> channelReadyCallbacks;

        synchronized (lock) {
            channelReadyCallback = this.channelReadyCallback;
            channelReadyCallbacks = this.channelReadyCallbacks;
            this.channelReadyCallback = null;
            this.channelReadyCallbacks = null;
        }

        if (channelReadyCallback != null) {
            channelReadyCallback.run();
        }

        if (channelReadyCallbacks != null) {
            for (Runnable callback : channelReadyCallbacks) {
                callback.run();
            }
        }
    }
}
