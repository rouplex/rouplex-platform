package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.utils.BufferUtils;
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

    TcpReadChannel(TcpClient tcpClient) {
        super(tcpClient, ChannelType.Read);
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

//    @GuardedBy("lock")
//    private int readBuffered(ByteBuffer bb) throws IOException {
//        int total = BufferUtils.transfer(byteBuffer, bb);
//        if (bb.hasRemaining() && !onlyAsyncReadWrite) {
//            int read = socketChannel.read(byteBuffer);
//            if (read != )
//        }
//    }
//
    @Override
    public int read(ByteBuffer byteBuffer) throws IOException {
        ValidationUtils.checkedNotNull(byteBuffer, "byteBuffer");

        int read;
        synchronized (lock) {
            if (eos) {
                return -1; // a previous read = -1 has sealed the status of the channel as ok and eos
            }

            if (blocked) {
                throw new IOException("TcpReadChannel cannot perform concurrent blocking reads");
            }

            try {
                if (timeoutMillis == -1) {
                    read = socketChannel.read(byteBuffer);
                } else {
                    blocked = true;
                    long startTimestamp = System.currentTimeMillis();

                    while ((read = socketChannel.read(byteBuffer)) == 0) {
                        long remainingMillis = System.currentTimeMillis() - (startTimestamp + timeoutMillis);
                        if (remainingMillis <= 0) {
                            break;
                        }

                        addChannelReadyCallback(notifyAllCallback);
                        lock.wait(remainingMillis);
                    }
                }
            } catch (InterruptedException ie) {
                read = 0;
            } catch (IOException ioe) {
                tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION,
                        ioe, tcpClient.tcpSelector.tcpSelectorThread == Thread.currentThread());
                throw ioe;
            } finally {
                blocked = false;
            }

            eos = read == -1;
        }

        if (eos) {
            tcpClient.tcpWriteChannel.handleEos();
        }

        return read;
    }
}
