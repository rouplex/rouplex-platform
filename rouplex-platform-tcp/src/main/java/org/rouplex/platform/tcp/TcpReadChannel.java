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
                return socketChannel.read(byteBuffer);
            }

            // Can't hold broker tcpSelectorThread for too long, since we would be penalising other clients of the broker
            if (tcpSelector.tcpSelectorThread == Thread.currentThread()) {
                throw new IOException("Channel cannot perform blocking reads from broker thread");
            }

            int read;
            currentByteBuffer = byteBuffer;
            long startTimestamp = System.currentTimeMillis();

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

    @Override
    void asyncAddChannelReadyCallback(Runnable channelReadyCallback) throws IOException {
        tcpSelector.asyncAddTcpReadChannel(tcpClient, channelReadyCallback);
    }
}
