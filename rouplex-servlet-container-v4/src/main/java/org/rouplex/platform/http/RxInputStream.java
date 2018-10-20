package org.rouplex.platform.http;

import org.rouplex.commons.utils.BufferUtils;
import org.rouplex.platform.io.ReactiveReadChannel;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
class RxInputStream extends ServletInputStream {
    private final Object lock = new Object();
    private final ReactiveReadChannel reactiveReadChannel;

    private final byte[] oneByteBuffer = new byte[1];
    private ByteBuffer buffered;
    private ChannelListener channelListener;
    private IOException ioe;
    private boolean ready;
    private boolean eos;

    private class ChannelListener implements Runnable {
        private final ReadListener readListener;

        ChannelListener(ReadListener readListener) {
            this.readListener = readListener;
        }

        @Override
        public void run() {
            IOException ioe;
            boolean eos;
            synchronized (lock) {
                ready = true;
                ioe = RxInputStream.this.ioe;
                eos = RxInputStream.this.eos;
            }

            try {
                if (ioe != null) {
                    readListener.onError(ioe);
                }
                else if (eos) {
                    readListener.onAllDataRead();
                }
                else {
                    readListener.onDataAvailable();
                    reactiveReadChannel.addChannelReadyCallback(this);
                }
            } catch (Exception e) {
                // exception thrown by user code while handling event -- what now?
            }
        }
    }

    RxInputStream(ByteBuffer buffered, ReactiveReadChannel reactiveReadChannel) {
        this.buffered = buffered.hasRemaining() ? buffered : null;
        this.reactiveReadChannel = reactiveReadChannel;
        reactiveReadChannel.setTimeout(0); // blocking
    }

    @Override
    public boolean isFinished() {
        synchronized (lock) {
            return ioe != null || eos;
        }
    }

    @Override
    public boolean isReady() {
        synchronized (lock) {
            return ready;
        }
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        synchronized (lock) {
            if (channelListener != null) {
                throw new IllegalStateException("ReadListener is already set");
            }

            if (readListener == null) {
                throw new NullPointerException("ReadListener cannot be null");
            }

            channelListener = new ChannelListener(readListener);
        }

        reactiveReadChannel.setTimeout(-1); // non-blocking

        try {
            reactiveReadChannel.addChannelReadyCallback(channelListener);
        } catch (IOException ioe) {
            handleReadException(ioe);
        }
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        ByteBuffer destination = ByteBuffer.wrap(buffer, off, len);
        try {
            synchronized (lock) {
                if (ioe != null) {
                    throw ioe;
                }

                int read;
                if (buffered != null) {
                    read = BufferUtils.transfer(buffered, destination);
                    if (!buffered.hasRemaining()) {
                        buffered = null;
                    }
                } else {
                    read = reactiveReadChannel.read(destination);
                }

                eos = read == -1;
                ready = read != 0;
                return read;
            }
        } catch (IOException ioe) {
            throw handleReadException(ioe);
        }
    }

    @Override
    public int read() throws IOException {
        try {
            synchronized (lock) {
                int read = read(oneByteBuffer);
                return read > 0 ? oneByteBuffer[0] : read;
            }
        } catch (IOException ioe) {
            throw handleReadException(ioe);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            ioe = new IOException("Channel is closed");
        }
    }

    private IOException handleReadException(IOException ioe) {
        synchronized (lock) {
            this.ioe = ioe;

            // channelListener was never set (once set, it never goes back to null)
            if (channelListener == null) {
                return ioe;
            }
        }

        // read previous comment, we can never run into an NPE here, even if outside the synchronized block
        channelListener.run();
        return ioe;
    }
}
