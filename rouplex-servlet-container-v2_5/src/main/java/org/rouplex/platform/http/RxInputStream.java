package org.rouplex.platform.http;

import org.rouplex.platform.io.ReactiveReadChannel;

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
    private IOException ioe;
    private boolean ready;
    private boolean eos;

    RxInputStream(ByteBuffer buffered, ReactiveReadChannel reactiveReadChannel) {
        this.buffered = buffered.hasRemaining() ? buffered : null;
        this.reactiveReadChannel = reactiveReadChannel;
        reactiveReadChannel.setTimeout(0); // blocking
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        return 0;
    }

    @Override
    public int read() throws IOException {
        return 0;
    }

    @Override
    public void close() throws IOException {
    }
}
