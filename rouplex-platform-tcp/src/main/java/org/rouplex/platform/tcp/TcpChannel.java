package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.utils.BufferUtils;
import org.rouplex.commons.utils.ValidationUtils;
import org.rouplex.platform.io.ReactiveChannel;

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
public abstract class TcpChannel implements ReactiveChannel {
    protected enum ChannelType {
        Read, Write
    }

    protected enum BufferingType {
        None, Direct, Heap
    }

    protected final Object lock = new Object();
    protected final ChannelType channelType;
    protected final TcpClient tcpClient;
    protected final TcpReactor.TcpSelector tcpSelector; // cache for performance
    protected final SocketChannel socketChannel; // cache for performance

    // Tandem fields to keep Garbage Collection at minimum
    @GuardedBy("no-need-guarding") protected Runnable channelReadyCallback;
    @GuardedBy("no-need-guarding") protected List<Runnable> channelReadyCallbacks;

    @GuardedBy("lock") protected boolean blocked;
    @GuardedBy("lock") protected int timeoutMillis = -1;
    @GuardedBy("lock") protected boolean eos;

    @GuardedBy("lock") protected int desiredBufferSize = -1;
    @GuardedBy("lock") protected ByteBuffer byteBuffer;
    protected final BufferingType bufferingType;
    protected final boolean onlyAsyncReadWrite;

    protected final Runnable notifyAllCallback = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    };

    protected TcpChannel(TcpClient tcpClient, ChannelType channelType) {
        this.tcpClient = tcpClient;
        this.tcpSelector = tcpClient.tcpSelector;
        this.socketChannel = (SocketChannel) tcpClient.selectableChannel;
        this.onlyAsyncReadWrite = tcpClient.builder.onlyAsyncReadWrite;
        this.channelType = channelType;

        int bufferSize = channelType == ChannelType.Read
                ? tcpClient.builder.readBufferSize : tcpClient.builder.writeBufferSize;

        this.bufferingType = bufferSize == 0 ? BufferingType.None
            : tcpClient.builder.useDirectBuffers ? BufferingType.Direct : BufferingType.Heap;

        switch (bufferingType){
            case None:
                this.byteBuffer = null;
                break;
            case Direct:
                this.byteBuffer = ByteBuffer.allocateDirect(bufferSize);
                break;
            case Heap:
                this.byteBuffer = ByteBuffer.allocate(bufferSize);
                break;
        }
    }

    public TcpClient getTcpClient() {
        return tcpClient;
    }

    /**
     * This call is performed once the opposing channel has received it own EOS. It first checks if the owning TcpClient
     * is configured to auto close on EOS, then it checks if this channel has received its EOS, and if so, it closes the
     * owning client.
     */
    protected void handleEos() throws IOException {
        if ((tcpClient.autoCloseMask & TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EOS.mask) != 0) {
            synchronized (lock) {
                if (!eos) {
                    return;
                }
            }

            tcpClient.closeAndAsyncUnregister(null);
        }
    }

    /**
     * The number of milliseconds that the channel is allowed to block performing an operation. This value can be
     * changed at any point, and any pending read/write operations must honor the new value immediately.
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
    public void setTimeout(int timeoutMillis) {
        synchronized (lock) {
            this.timeoutMillis = timeoutMillis == 0 ? Integer.MAX_VALUE : timeoutMillis;
            lock.notifyAll();
        }
    }

    public void setBufferSize(int bufferSize) {
        if (byteBuffer == null) {
            throw new IllegalArgumentException("This channel is not buffered, so bufferSize cannot be set");
        }

        if (bufferSize <= 0) {
            ValidationUtils.checkedNotNegative(bufferSize, "bufferSize");
            throw new IllegalArgumentException("This channel is buffered, so bufferSize must be positive");
        }

        synchronized (lock) {
            desiredBufferSize = bufferSize;
            ensureBufferSize();
        }
    }

    public int getBufferSize() {
        synchronized (lock) {
            return desiredBufferSize == -1 ? byteBuffer.capacity() : desiredBufferSize;
        }
    }

    @GuardedBy("lock")
    protected void ensureBufferSize() {
        if (byteBuffer.position() <= desiredBufferSize) {
            ByteBuffer newByteBuffer = bufferingType == BufferingType.Direct
                    ? ByteBuffer.allocateDirect(desiredBufferSize)
                    : ByteBuffer.allocate(desiredBufferSize);

            byteBuffer.flip();
            BufferUtils.transfer(byteBuffer, newByteBuffer);
            byteBuffer = newByteBuffer;
            desiredBufferSize = -1; // reset
            lock.notifyAll();
        }
    }

    /**
     * Set a maximum rate that this channel is allowed to reach.
     * By default this value is -1, meaning there is no rate limit.
     *
     * The rate is expressed in "bytes per duration of timeUnit". As an example:
     * maxRate=100 (bytes), duration=1, timeUnit=TimeUnit.MILLIS is different from
     * maxRate=1000 (bytes), duration=10, timeUnit=TimeUnit.MILLIS since the former one is calculated every
     * millisecond and the later is calculated every 10 milliseconds (allowing for occasional bursts, as long as there
     * are no more than 1000 bytes during the 10 milliseconds)
     *
     * @param maxRate  The maximum rate in number of bytes.
     * @param duration The duration for which the maxRate has to be capped.
     * @param timeUnit The time unit related to the duration parameter.
     */
    public void setMaxRate(long maxRate, long duration, TimeUnit timeUnit) {
        throw new UnsupportedOperationException("setMaxRate() is not supported yet by this channel");
    }

    @Override
    public void addChannelReadyCallback(final Runnable channelReadyCallback) throws IOException {
        if (Thread.currentThread() == tcpSelector.tcpSelectorThread) {
            syncAddChannelReadyCallback(channelReadyCallback); // most common
            return;
        }

        if (bufferingType == BufferingType.None) {
            tcpSelector.asyncAddTcpChannelCallback(tcpClient, channelType, channelReadyCallback);
            return;
        }

        synchronized (lock) {
            if (byteBuffer.position() == 0) {
                tcpSelector.asyncAddTcpChannelCallback(tcpClient, channelType, channelReadyCallback);
                return;
            }
        }

        if (tcpClient.eventsExecutor == null) {
            try {
                channelReadyCallback.run();
            } catch (RuntimeException re) {
                tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, false);
            }

            return;
        }

        tcpClient.eventsExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    channelReadyCallback.run();
                } catch (RuntimeException re) {
                    tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, false);
                }
            }
        });
    }

    protected void syncAddChannelReadyCallback(Runnable channelReadyCallback) {
        if (this.channelReadyCallback == null) {
            this.channelReadyCallback = channelReadyCallback;
            tcpSelector.updatedTcpClients.add(tcpClient); // avoid adding the client more than once
            return;
        }

        if (channelReadyCallbacks == null) {
            channelReadyCallbacks = new ArrayList<Runnable>();
        }

        channelReadyCallbacks.add(channelReadyCallback);
    }

    /**
     * Get notification from selector that this channel can perform the op, and forward such notification to
     * eventual listeners.
     */
    protected void syncHandleChannelReady() {
        // todo remove this if-statement once assessed channelReadyCallback is never null
        if (this.channelReadyCallback == null) {
            throw new Error("Internal Error. Assessment 'channelReadyCallback != null' failed");
        }

        final Runnable channelReadyCallback = this.channelReadyCallback;
        final List<Runnable> channelReadyCallbacks = this.channelReadyCallbacks;
        this.channelReadyCallback = null;
        this.channelReadyCallbacks = null;

        if (tcpClient.eventsExecutor != null) {
            tcpClient.eventsExecutor.execute(channelReadyCallback);

            if (channelReadyCallbacks != null) {
                for (Runnable callback : channelReadyCallbacks) {
                    tcpClient.eventsExecutor.execute(callback);
                }
            }
        } else {
            boolean userExceptionHandledIfPresent = false;
            try {
                channelReadyCallback.run();
            } catch (RuntimeException re) {
                userExceptionHandledIfPresent = true;
                tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, true);
            }

            if (channelReadyCallbacks != null) {
                for (Runnable callback : channelReadyCallbacks) {
                    try {
                        callback.run();
                    } catch (RuntimeException re) {
                        if (userExceptionHandledIfPresent) {
                            continue;
                        }

                        userExceptionHandledIfPresent = true;
                        tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_USER_CALLBACK_EXCEPTION, re, true);
                    }
                }
            }
        }
    }
}