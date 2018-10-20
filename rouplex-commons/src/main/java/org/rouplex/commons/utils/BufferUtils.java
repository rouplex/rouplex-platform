package org.rouplex.commons.utils;

import java.nio.ByteBuffer;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class BufferUtils {

    public static int transfer(ByteBuffer source, ByteBuffer destination, int maxBytes) {
        int transfer = Math.min(destination.remaining(), Math.min(source.remaining(), maxBytes));

        if (transfer > 0) {
            if (source.isDirect() || destination.isDirect()) {
                int limit = source.limit();
                source.limit(source.position() + transfer);
                destination.put(source);
                source.limit(limit);
            } else {
                System.arraycopy(source.array(), source.position(),
                    destination.array(), destination.position(), transfer);

                source.position(source.position() + transfer);
                destination.position(destination.position() + transfer);
            }
        }

        return transfer;
    }

    public static int transfer(ByteBuffer source, ByteBuffer destination) {
        return transfer(source, destination, Integer.MAX_VALUE);
    }

    /**
     * Copy bytes from source to destination, adjusting their positions accordingly. This call does not fail if
     * destination cannot accommodate all the bytes available in source, but copies as many as possible.
     *
     * @param source
     *          Source buffer
     * @param destination
     *          Destination buffer
     * @param maxBytes
     *          The maximum number of bytes to copy, subject to limits of bytes on source and space on destination
     * @return
     *          The number of bytes copied
     */
    public static int checkArgsAndTransfer(ByteBuffer source, ByteBuffer destination, int maxBytes) {
        return transfer(
            ValidationUtils.checkedNotNull(source, "source"),
            ValidationUtils.checkedNotNull(destination, "destination"),
            ValidationUtils.checkedNotNegative(maxBytes, "maxBytes")
        );
    }

    /**
     * Copy bytes from source to destination, adjusting their positions accordingly. This call does not fail if
     * destination cannot accommodate all the bytes available in source, but copies as many as possible.
     *
     * @param source
     *          Source buffer
     * @param destination
     *          Destination buffer
     * @return
     *          The number of bytes copied
     */
    public static int checkArgsAndTransfer(ByteBuffer source, ByteBuffer destination) {
        return transfer(
            ValidationUtils.checkedNotNull(source, "source"),
            ValidationUtils.checkedNotNull(destination, "destination")
        );
    }
}
