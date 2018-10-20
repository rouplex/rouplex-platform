package org.rouplex.platform.tcp;

import org.rouplex.commons.utils.ValidationUtils;
import org.rouplex.platform.io.ReactiveReadChannel;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A channel providing read functionality over Tcp.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class TcpReadChannel extends TcpChannel implements ReactiveReadChannel {
    private final ByteBuffer oneByteBB = ByteBuffer.allocate(1);

    TcpReadChannel(TcpClient tcpClient, int bufferSize) {
        super(tcpClient, bufferSize);
    }

    @Override
    public int read() throws IOException {
        int read;
        synchronized (lock) {
            if (eos) {
                return -1; // a previous read = -1 has sealed the status of the channel as ok and eos
            }

            read = read(oneByteBB);

            if (read > 0) {
                read = oneByteBB.array()[0];
                oneByteBB.clear();
            }

            eos = read == -1;
        }

        if (eos) {
            tcpClient.tcpWriteChannel.handleEos();
        }

        return read;
    }

    @Override
    public int read(ByteBuffer byteBuffer) throws IOException {
        ValidationUtils.checkedNotNull(byteBuffer, "byteBuffer");

        int read;
        synchronized (lock) {
            if (eos) {
                return -1; // a previous read = -1 has sealed the status of the channel as ok and eos
            }

            if (blocked) {
                throw new IOException("ReadChannel cannot perform concurrent blocking reads");
            }

            if (timeoutMillis == -1) {
                try {
                    read = socketChannel.read(byteBuffer);
                } catch (IOException ioe) {
                    tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION,
                            ioe, tcpClient.tcpSelector.tcpSelectorThread == Thread.currentThread());
                    throw ioe;
                }
            } else {
                // Don't penalise other TcpClients on same selector/thread by blocking here
                if (tcpSelector.tcpSelectorThread == Thread.currentThread()) {
                    throw new IOException("ReadChannel cannot perform blocking reads from reactor thread");
                }

                blocked = true;
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
                } catch (IOException ioe) {
                    tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION,
                            ioe, tcpClient.tcpSelector.tcpSelectorThread == Thread.currentThread());
                    throw ioe;
                }

                blocked = false;
            }

            eos = read == -1;
        }

        if (eos) {
            tcpClient.tcpWriteChannel.handleEos();
        }

        return read;
    }

    @Override
    void asyncAddChannelReadyCallback(Runnable channelReadyCallback) throws IOException {
        tcpSelector.asyncAddTcpReadChannel(tcpClient, channelReadyCallback);
    }
}
