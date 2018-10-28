package org.rouplex.tcp;

import org.rouplex.commons.annotations.Nullable;
import org.rouplex.platform.tcp.TcpClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

class EchoRequester extends EchoAbstract {
    protected final ByteBuffer writeByteBuffer;
    protected final ByteBuffer readByteBuffer;
    protected final InputStream inputStream;
    protected final OutputStream outputStream;

    protected int unResponded;
    protected boolean inputStreamEnded;
    protected boolean sentEos;

    protected EchoRequester(TcpClient tcpClient, EchoCounts echoCounts,
                            int echoRequesterBufferSize, InputStream inputStream, @Nullable OutputStream outputStream) {
        super(tcpClient, echoCounts);

        writeByteBuffer = ByteBuffer.allocate(echoRequesterBufferSize);
        readByteBuffer = ByteBuffer.allocate(echoRequesterBufferSize);

        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    @Override
    protected void start() {
        pumpRequest();
        pumpResponse();
    }

    @Override
    protected void pumpRequest() {
        int readFromInputStream;
        try {
            readFromInputStream = inputStream.read(writeByteBuffer.array(), writeByteBuffer.position(),
                    writeByteBuffer.limit() - writeByteBuffer.position());

            synchronized (writeByteBuffer) {
                switch (readFromInputStream) {
                    case -1:
                        inputStreamEnded = true;
                    case 0:
                        break;
                    default:
                        unResponded += readFromInputStream;
                        writeByteBuffer.position(writeByteBuffer.position() + readFromInputStream);
                }
            }
        } catch (IOException ioe) {
            throw new Error("InputStream threw unexpected exception", ioe);
        }

        if (checkShutdown()) {
            return;
        }

        if (writeByteBuffer.position() > 0) {
            writeByteBuffer.flip();
            report(String.format("%s sending [%s]", tcpClient.getDebugId(),
                    new String(writeByteBuffer.array(), 0, writeByteBuffer.limit())));

            try {
                tcpClient.getWriteChannel().write(writeByteBuffer);
            } catch (Exception ioe) {
                echoCounts.failedWrite.incrementAndGet();
                // by default the tcpClient gets closed on exceptions, nothing to do here
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                return;
            }

            report(String.format("%s sent [%s]", tcpClient.getDebugId(),
                    new String(writeByteBuffer.array(), 0, writeByteBuffer.position())));
            writeByteBuffer.compact();
        }

        if (readFromInputStream > 0) {
            try {
                // we have more bytes to send, either in inputStream or in writeByteBuffer
                tcpClient.getWriteChannel().addChannelReadyCallback(pumpRequest);
            } catch (IOException ioe) {
                // add counter
                // by default the tcpClient gets closed on exceptions, nothing to do here
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
            }
        }
    }

    @Override
    protected void pumpResponse() {
        int read;
        try {
            read = tcpClient.getReadChannel().read(readByteBuffer);
        } catch (IOException ioe) {
            echoCounts.failedRead.incrementAndGet();
            // by default the tcpClient gets closed on exceptions, nothing to do here
            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
            return;
        }

        try {
            switch (read) {
                case -1:
                    echoCounts.receivedEos.incrementAndGet();
                    report(String.format("%s received eos", tcpClient.getDebugId()));
                    if (checkShutdown()) {
                        return;
                    }
                    break;
                case 0:
                    checkShutdown();
                    // nothing changed, just read some more when there inputStream bytes available for reading
                    tcpClient.getReadChannel().addChannelReadyCallback(pumpResponse);
                    break;
                default:
                    synchronized (writeByteBuffer) {
                        unResponded -= read;
                    }
                    String payload = new String(readByteBuffer.array(), readByteBuffer.position() - read, read);
                    report(String.format("%s received [%s]", tcpClient.getDebugId(), payload));
                    if (outputStream != null) {
                        outputStream.write(readByteBuffer.array(), readByteBuffer.position() - read, read);
                    }
                    readByteBuffer.clear();
                    pumpResponse();
            }
        } catch (IOException ioe) {
            // by default the tcpClient gets closed on exceptions, nothing to do here
            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }

    private boolean checkShutdown() {
        synchronized (writeByteBuffer) {
            if (sentEos) {
                return true;
            }

            if (!inputStreamEnded || unResponded != 0) {
                return false;
            }

            sentEos = true;
            shutdownOutput();
            return true;
        }
    }
}