package org.rouplex.platform.tcp;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A channel providing write functionality over Tcp.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpReadChannel extends TcpChannel {
    TcpReadChannel(TcpClient tcpClient) {
        super(tcpClient);
    }

    public int read(ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer == null) {
            throw new IllegalArgumentException("ByteBuffer cannot be null");
        }

        synchronized (lock) {
            if (currentByteBuffer != null) {
                throw new IOException("Channel cannot perform concurrent reads");
            }

            if (timeoutMillis == -1) {
                int read = socketChannel.read(byteBuffer);

                // spare a comparison, it's ok to allow non broker threads update this state
                channelReady = read != 0;
                return read;
            }

            // Can't hold broker thread for too long, since we would be penalising other clients of the broker
            if (tcpClient.brokerThread == Thread.currentThread()) {
                throw new IOException("Channel cannot perform blocking reads from broker thread");
            }

            int read;
            currentByteBuffer = byteBuffer;
            long startTimestamp = System.currentTimeMillis();
            socketChannel.write(byteBuffer);

            try {
                while ((read = socketChannel.read(byteBuffer)) == 0) {
                    long remainingMillis = System.currentTimeMillis() - (startTimestamp + timeoutMillis);
                    if (remainingMillis <= 0) {
                        break;
                    }

                    addChannelReadyCallback(notifyAllCallback);
                    lock.wait(remainingMillis);
                }
            } catch (InterruptedException ie) {
                read = 0;
            }

            currentByteBuffer = null;
            return read;
        }
    }
}
