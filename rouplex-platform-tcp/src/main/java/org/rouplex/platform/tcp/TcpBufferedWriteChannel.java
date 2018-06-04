package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A channel providing buffered write functionality over Tcp.
 *
 * Each write call copies as many bytes possible from the buffer argument to an internal buffer, sets up the listener
 * for WriteReady event and returns. The content is then written asynchronously during the callback event in the context
 * of the TcpReactor thread.
 *
 * This can be useful when the underlying socket channel is encrypting the data and the caller would not want to incur
 * any encryption delays.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpBufferedWriteChannel extends TcpWriteChannel {
    private final TcpWriteChannel writeChannel;
    private final boolean onlyAsync;

    @GuardedBy("lock (content)") private final ByteBuffer byteBuffer;
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

    public TcpBufferedWriteChannel(TcpWriteChannel writeChannel, int bufferSize, boolean onlyAsync, boolean useDirectBuffers) {
        super(writeChannel.tcpClient);

        this.writeChannel = writeChannel;
        this.byteBuffer = useDirectBuffers ? ByteBuffer.allocateDirect(bufferSize) : ByteBuffer.allocate(bufferSize);
        this.onlyAsync = onlyAsync;
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

                if ((copied = transfer(bb, byteBuffer)) != 0) {
                    writeChannel.addChannelReadyCallback(pump);
                }
            } else {
                copied = transfer(bb, byteBuffer);
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

    /**
     * Copy bytes from source to destination, adjusting their positions accordingly. This call does not fail if
     * destination cannot accommodate all the bytes available in source, but copies as many as possible.
     *
     * @param source
     *          Source buffer
     * @param destination
     *          Destination buffer
     * @return
     *          The number of bytes copied
     */
    private int transfer(ByteBuffer source, ByteBuffer destination) {
        int srcRemaining;
        int destRemaining;
        int transferred;

        if ((srcRemaining = source.remaining()) > (destRemaining = destination.remaining())) {
            if (source.isDirect() || destination.isDirect()) {
                int limit = source.limit();
                source.limit(source.position() + destRemaining);
                destination.put(source);
                source.limit(limit);
                return destRemaining;
            }

            transferred = destRemaining;
        } else {
            if (source.isDirect() || destination.isDirect()) {
                destination.put(source);
                return srcRemaining;
            }

            transferred = srcRemaining;
        }

        if (transferred > 0) {
            System.arraycopy(source.array(), source.position(),
                destination.array(), destination.position(), transferred);

            source.position(source.position() + transferred);
            destination.position(destination.position() + transferred);
        }

        return transferred;
    }
}
