package org.rouplex.platform.io;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ReactiveWriteChannel extends ReactiveChannel {
    int write(ByteBuffer byteBuffer) throws IOException;
    void shutdown() throws IOException;
}
