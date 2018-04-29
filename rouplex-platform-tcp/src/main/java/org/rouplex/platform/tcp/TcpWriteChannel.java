package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A channel providing write functionality over Tcp.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpWriteChannel extends TcpChannel {
    @GuardedBy("lock") protected boolean shutdown;

    TcpWriteChannel(TcpClient tcpClient) {
        super(tcpClient);
    }

    public int write(ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer == null) {
            throw new IllegalArgumentException("ByteBuffer cannot be null");
        }

        synchronized (lock) {
            if (shutdown) {
                throw new IOException("Channel cannot perform writes after shutdown");
            }

            if (currentByteBuffer != null) {
                throw new IOException("Channel cannot perform concurrent writes");
            }

            if (timeoutMillis == -1) {
                int written = socketChannel.write(byteBuffer);

                // spare a comparison, it's ok to allow non broker threads update this state
                channelReady = written != 0;
                return written;
            }

            // Can't hold broker thread for too long, since we would be penalising other clients of the broker
            if (tcpClient.brokerThread == Thread.currentThread()) {
                throw new IOException("Channel cannot perform blocking writes from broker thread");
            }

            currentByteBuffer = byteBuffer;
            int initialPosition = byteBuffer.position();
            long startTimestamp = System.currentTimeMillis();
            socketChannel.write(byteBuffer);

            try {
                while (byteBuffer.hasRemaining()) {
                    long remainingMillis = System.currentTimeMillis() - (startTimestamp + timeoutMillis);
                    if (remainingMillis <= 0) {
                        break;
                    }

                    addChannelReadyCallback(notifyAllCallback);

                    lock.wait(remainingMillis);
                    socketChannel.write(byteBuffer);
                }
            } catch (InterruptedException ie) {
                // just return with whatever has been written
            }

            currentByteBuffer = null;
            return byteBuffer.position() - initialPosition;
        }
    }

    public void shutdown() throws IOException {
        synchronized (lock) {
            if (shutdown) {
                throw new IOException("Channel cannot perform writes after shutdown");
            }

            if (currentByteBuffer != null) {
                throw new IOException("Channel cannot perform concurrent writes");
            }

            shutdown = true;
            socketChannel.shutdownOutput(); // todo verify the selector returns from select with write intops
        }
    }
}
