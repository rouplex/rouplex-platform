package org.rouplex.platform.io;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ReactiveReadChannel extends ReactiveChannel {
    int read() throws IOException;
    int read(ByteBuffer byteBuffer) throws IOException;
}
