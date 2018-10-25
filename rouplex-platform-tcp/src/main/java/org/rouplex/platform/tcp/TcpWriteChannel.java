package org.rouplex.platform.tcp;

import org.rouplex.commons.annotations.GuardedBy;
import org.rouplex.commons.utils.BufferUtils;
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
    protected TcpWriteChannel(TcpClient tcpClient) {
        super(tcpClient, ChannelType.Write);
    }

    @GuardedBy("lock") protected boolean shutdownRequested;

    // Only used when buffering is enabled
    private final Runnable writeToChannelThenNotify = new Runnable() {
        @Override
        public void run() {
            try {
                synchronized (lock) {
                    byteBuffer.flip();
                    socketChannel.write(byteBuffer);
                    byteBuffer.compact();
                    ensureBufferSize();

                    if (byteBuffer.position() > 0) {
                        addChannelReadyCallback(this);
                    } else if (shutdownRequested) {
                        eos = true;
                    }

                    lock.notifyAll();
                }
            } catch (IOException ioe) {
                // outgoing event fired without any lock acquired
                tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION, ioe, false);

                synchronized (lock) {
                    lock.notifyAll();
                }
            }

            if (eos) {
                try {
                    shutdownSocketChannelAndHandleEos();
                } catch (IOException ioe) {
                    /*
                      By default the TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION is set, so the TcpClient will
                      be disconnected and the client will be fired the new event
                      If TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION flag has been unset, then this exception
                      will be lost, and without a way to tell the user about it. This is true in the classic socket
                      channels as well, and the reason that any communication must be deemed successful upon receiving
                      EOS from the read channel
                    */
                }
            }
        }
    };

    @Override
    public int write(ByteBuffer byteBuffer) throws IOException {
        ValidationUtils.checkedNotNull(byteBuffer, "byteBuffer");

        synchronized (lock) {
            if (shutdownRequested) {
                throw new IOException("WriteChannel cannot perform writes after shutdown");
            }

            if (blocked) {
                throw new IOException("WriteChannel cannot perform concurrent blocking writes");
            }

            if (!byteBuffer.hasRemaining()) {
                return 0;
            }

            // initializing b/c compiler is complaining 'value may not be set further down' (compiler bug?)
            int written = 0;
            try {
                if (timeoutMillis == -1) {
                    written = writeNonBlocking(byteBuffer);
                } else {
                    blocked = true;
                    long startTimestamp = System.currentTimeMillis();

                    written = writeNonBlocking(byteBuffer);
                    while (byteBuffer.hasRemaining()) {
                        long remainingMillis = System.currentTimeMillis() - (startTimestamp + timeoutMillis);
                        if (remainingMillis <= 0) {
                            return written;
                        }

                        lock.wait(remainingMillis);
                        written += writeNonBlocking(byteBuffer);
                    }
                }
            } catch (InterruptedException ie) {
                // just return the number of bytes written prior to this exception
            } catch (IOException ioe) {
                tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION,
                        ioe, tcpClient.tcpSelector.tcpSelectorThread == Thread.currentThread());
                throw ioe;
            } finally {
                blocked = false;
            }

            return written;
        }
    }

    @GuardedBy("lock")
    private int writeNonBlocking(ByteBuffer byteBuffer) throws IOException {
        if (this.byteBuffer == null) {  // no buffering
            return socketChannel.write(byteBuffer);
        }

        int written;
        if (onlyAsyncReadWrite) {
            written = BufferUtils.transfer(byteBuffer, this.byteBuffer);
        } else {
            if (this.byteBuffer.position() != 0) {
                // flush not empty this.byteBuffer
                this.byteBuffer.flip();
                socketChannel.write(this.byteBuffer);
                this.byteBuffer.compact();
            }

            written = this.byteBuffer.position() == 0 ? socketChannel.write(byteBuffer) : 0;
            written += BufferUtils.transfer(byteBuffer, this.byteBuffer);
        }

        if (this.byteBuffer.position() != 0) {
            addChannelReadyCallback(writeToChannelThenNotify);
        }

        return written;
    }

    @Override
    public void shutdown() throws IOException {
        synchronized (lock) {
            if (blocked) {
                throw new IOException("WriteChannel cannot be shutdown during ongoing write");
            }

            if (shutdownRequested) {
                return;
            }

            shutdownRequested = true;

            if (byteBuffer != null && byteBuffer.position() > 0) {
                return;
            }

            eos = true;
        }

        shutdownSocketChannelAndHandleEos();
    }

    private void shutdownSocketChannelAndHandleEos() throws IOException {
        try {
            // jdk1.7+ socketChannel.shutdownOutput()
            // todo verify the selector returns from select with write ops
            socketChannel.socket().shutdownOutput();
            tcpClient.tcpReadChannel.handleEos();
        } catch (IOException ioe) {
            tcpClient.handleException(TcpEndPoint.AutoCloseCondition.ON_CHANNEL_EXCEPTION, ioe, false);
            throw ioe;
        }
    }
}
