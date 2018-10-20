package org.rouplex.platform.util;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.builders.MultiInstanceBuilder;
import org.rouplex.commons.utils.BufferUtils;
import org.rouplex.platform.tcp.TcpWriteChannel;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A channel providing buffered write functionality.
 *
 * This implementation can be configured to perform
 *
 * Each write call writes as many bytes possible and then copies in the internal buffer any leftover data, up to the
 * capacity; the rest is kept in the callers buffer. If any data is present in the internal buffer upon returning from
 * a write call, a listener/watcher for WriteReady is set up. The content is then written asynchronously during the
 * callback event in the context of the TcpReactor thread.
 *
 * This can be useful when the underlying socket channel is encrypting the data and the caller would not want to incur
 * any encryption delays.
 *
 * The size of the internal buffer can be adjusted even after it is set, and the new setting takes effect immediately.
 * In the case the internal buffer happens to already have more bytes than the new requested buffer size, the instance
 * will not accept any more bytes written to (will act as if buffer is full) until the desired buffer size can be
 * achieved, and a new internal buffer, reflecting the new capacity, will then be created.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpBufferedWriteChannel extends TcpWriteChannel {
    public static class Builder extends TcpBufferedWriteChannelBuilder<TcpBufferedWriteChannel, Builder> {
        public Builder(TcpWriteChannel writeChannel) {
            super(writeChannel);
        }

        /**
         * Build the BufferedWriteChannel and return it.
         *
         * @return
         *          The built BufferedWriteChannel
         */
        @Override
        public TcpBufferedWriteChannel build() throws Exception {
            return buildTcpBufferedWriteChannel();
        }
    }

    /**
     * A TcpBufferedWriteChannel builder. The builder can only build one buffeed channel, and once done, any future
     * calls to alter the builder or try to rebuild will fail with {@link IllegalStateException}.
     *
     * Not thread safe.
     */
    protected abstract static class TcpBufferedWriteChannelBuilder<T, B extends TcpBufferedWriteChannelBuilder>
        extends MultiInstanceBuilder<T, B> {

        protected TcpWriteChannel writeChannel;
        protected int bufferSize = 0x4000;
        protected boolean onlyAsync;
        protected boolean useDirectBuffers;

        protected TcpBufferedWriteChannelBuilder(TcpWriteChannel writeChannel) {
           this.writeChannel = writeChannel;
        }

        /**
         * The initial buffer size. Otherwise a default of 16Kb will be used.
         *
         * @param bufferSize
         *          The size for the buffer holding unwritten data, in bytes.
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return builder;
        }

        /**
         * If true, all the calls to write to the channel will result in the content being written to the buffer, and
         * the call returning immediately. In that case, the calling thread may be waiting the least in the case of an
         * ssl channel, since the heavier task of performing the crypto work is done in the context of another thread.
         *
         * If set to false, during a write call, there will be an attempt to send as many bytes as possible, and the
         * rest will be kept in the internal buffer, up to the point it has capacity (the rest will be left in the
         * caller's {@link ByteBuffer}. False is the default setting.
         *
         * @param onlyAsync
         *          True for always writing the bytes asynchronously, false otherwise
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withOnlyAsync(boolean onlyAsync) {
            this.onlyAsync = onlyAsync;
            return builder;
        }

        /**
         * If true the class will internally create {@link java.nio.DirectByteBuffer}, otherwise it will create a
         * {@link ByteBuffer}. Default is false.
         *
         * @param useDirectBuffers
         *          True for internally creating {@link java.nio.DirectByteBuffer}, false for {@link ByteBuffer}
         * @return
         *          The reference to this builder for chaining calls
         */
        public B withUseDirectBuffers(boolean useDirectBuffers) {
            this.useDirectBuffers = useDirectBuffers;
            return builder;
        }

        @Override
        protected void checkCanBuild() {
            // this builder can always build
        }

        protected TcpBufferedWriteChannel buildTcpBufferedWriteChannel() throws Exception {
            checkCanBuild();
            return new TcpBufferedWriteChannel(this);
        }
    }

    private final TcpWriteChannel writeChannel;
    private final boolean useDirectBuffers;
    private final boolean onlyAsync;

    @GuardedBy("lock") private int desiredBufferSize;
    @GuardedBy("lock") private ByteBuffer byteBuffer;
    @GuardedBy("lock") private boolean shutdownRequested;
    @GuardedBy("lock") private IOException pendingException;

    private final Runnable pump = new Runnable() {
        @Override
        public void run() {
            synchronized (writeChannel) {
                try {
                    byteBuffer.flip();
                    writeChannel.write(byteBuffer);
                    byteBuffer.compact();

                    ensureBufferSize();

                    if (byteBuffer.position() > 0) {
                        writeChannel.addChannelReadyCallback(this);
                    } else if (shutdownRequested) {
                        writeChannel.shutdown();
                    }
                } catch (IOException ioe) {
                    pendingException = ioe;
                }
            }
        }
    };

    protected TcpBufferedWriteChannel(TcpBufferedWriteChannelBuilder builder) {
        super(builder.writeChannel.getTcpClient(), builder.bufferSize);

        this.writeChannel = builder.writeChannel;
        this.onlyAsync = builder.onlyAsync;
        this.desiredBufferSize = builder.bufferSize;
        this.useDirectBuffers = builder.useDirectBuffers;
        this.byteBuffer = builder.useDirectBuffers
            ? ByteBuffer.allocateDirect(desiredBufferSize)
            : ByteBuffer.allocate(desiredBufferSize);
    }

    public void setBufferSize(int bufferSize) {
        synchronized (writeChannel) {
            desiredBufferSize = bufferSize;
            ensureBufferSize();
        }
    }

    public int getBufferSize() {
        synchronized (writeChannel) {
            return desiredBufferSize != 0 ? desiredBufferSize : byteBuffer.capacity();
        }
    }

    @GuardedBy("lock")
    private void ensureBufferSize() {
        if (desiredBufferSize != 0 && byteBuffer.position() <= desiredBufferSize) {
            ByteBuffer newByteBuffer = useDirectBuffers
                ? ByteBuffer.allocateDirect(desiredBufferSize)
                : ByteBuffer.allocate(desiredBufferSize);

            byteBuffer.flip();
            BufferUtils.transfer(byteBuffer, newByteBuffer);
            byteBuffer = newByteBuffer;
            desiredBufferSize = 0;
        }
    }

    @Override
    public int write(ByteBuffer bb) throws IOException {
        synchronized (writeChannel) {
            if (pendingException != null) {
                throw pendingException;
            }

            if (shutdownRequested) {
                throw new IOException("Writer already shutdown");
            }

            if (!bb.hasRemaining()) {
                return 0;
            }

            int copied;
            if (byteBuffer.position() == 0) {
                if (!onlyAsync) {
                    writeChannel.write(bb);
                }

                if ((copied = BufferUtils.transfer(bb, byteBuffer)) != 0) {
                    writeChannel.addChannelReadyCallback(pump);
                }
            } else {
                copied = BufferUtils.transfer(bb, byteBuffer);
            }

            return copied;
        }
    }

    @Override
    public void shutdown() throws IOException {
        synchronized (writeChannel) {
            if (pendingException != null) {
                throw pendingException;
            }

            if (shutdownRequested) {
                throw new IOException("Writer already shutdown");
            }

            shutdownRequested = true;

            if (byteBuffer.position() == 0) {
                writeChannel.shutdown();
            }
        }
    }
}
