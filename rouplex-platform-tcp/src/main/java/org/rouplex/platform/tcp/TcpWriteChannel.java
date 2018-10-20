package org.rouplex.platform.tcp;

import org.rouplex.commons.utils.ValidationUtils;
import org.rouplex.platform.io.ReactiveWriteChannel;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A channel providing write functionality over Tcp.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpWriteChannel extends TcpChannel implements ReactiveWriteChannel {
    protected TcpWriteChannel(TcpClient tcpClient, int bufferSize) {
        super(tcpClient, bufferSize);
    }

    @Override
    public int write(ByteBuffer byteBuffer) throws IOException {
        ValidationUtils.checkedNotNull(byteBuffer, "byteBuffer");

        synchronized (lock) {
            if (eos) {
                throw new IOException("WriteChannel cannot perform writes after shutdown");
            }

            if (blocked) {
                throw new IOException("WriteChannel cannot perform concurrent blocking writes");
            }

            if (timeoutMillis == -1) {
                try {
                    return socketChannel.write(byteBuffer);
                } catch (IOException ioe) {
                    tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION,
                            ioe, tcpClient.tcpSelector.tcpSelectorThread == Thread.currentThread());
                    throw ioe;
                }
            }

            // Don't penalise other TcpClients on same selector/thread by blocking here
            if (tcpClient.tcpSelector.tcpSelectorThread == Thread.currentThread()) {
                throw new IOException("WriteChannel cannot perform blocking writes from reactor thread");
            }

            blocked = true;
            int initialPosition = byteBuffer.position();
            long startTimestamp = System.currentTimeMillis();

            try {
                // todo check if writing 0 bytes on a closed channel will produce ioe,
                // todo and it not, put following statement inside the loop
                socketChannel.write(byteBuffer);

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
            } catch (IOException ioe) {
                tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION,
                        ioe, tcpClient.tcpSelector.tcpSelectorThread == Thread.currentThread());
                throw ioe;
            }

            blocked = false;
            return byteBuffer.position() - initialPosition;
        }
    }

    @Override
    void asyncAddChannelReadyCallback(Runnable channelReadyCallback) throws IOException {
        tcpSelector.asyncAddTcpWriteChannel(tcpClient, channelReadyCallback);
    }

    @Override
    public void shutdown() throws IOException {
        synchronized (lock) {
            if (eos) {
                return;
            }

            if (blocked) {
                throw new IOException("WriteChannel cannot be shutdown during ongoing write");
            }

            eos = true;
        }

        try {
            socketChannel.socket().shutdownOutput(); // todo verify the selector returns from select with write ops
        } finally {
            tcpClient.tcpReadChannel.handleEos();
        }
    }
}
