package org.rouplex.tcp;

import org.rouplex.platform.tcp.TcpClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class EchoResponder extends EchoAbstract {
    private static Executor executor = Executors.newCachedThreadPool();
    protected final ByteBuffer byteBuffer;
    protected final boolean useExecutor;
    protected boolean readEos;

    protected EchoResponder(TcpClient tcpClient, EchoCounts echoCounts, int bufferSize, boolean useExecutor) {
        super(tcpClient, echoCounts);
        this.byteBuffer = ByteBuffer.allocate(bufferSize);
        this.useExecutor = useExecutor;
    }

    @Override
    void start() {
        pumpRequest();
    }

    @Override
    protected void pumpRequest() {
        boolean bufferFull;
        do {
            boolean bufferInitiallyEmpty;
            synchronized (byteBuffer) {
                bufferInitiallyEmpty = byteBuffer.position() == 0;
                int read;
                try {
                    read = tcpClient.getReadChannel().read(byteBuffer);
                } catch (IOException ioe) {
                    echoCounts.failedRead.incrementAndGet();
                    // by default the tcpClient gets closed on exceptions, nothing to do here
                    report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                    return;
                }

                try {
                    switch (read) {
                        case -1:
                            readEos = true;
                            echoCounts.receivedEos.incrementAndGet();
                            report(String.format("%s received eos", tcpClient.getDebugId()));
                            if (byteBuffer.position() == 0) {
                                // by default the tcpClient gets closed on both eos, nothing to do here
                                shutdownOutput();
                            }
                            return;
                        case 0:
                            // nothing changed, just read some more when there is bytes available for reading
                            tcpClient.getReadChannel().addChannelReadyCallback(pumpRequest);
                            return;
                        default:
                            String payload = new String(byteBuffer.array(), byteBuffer.position() - read, read);
                            report(String.format("%s received [%s]", tcpClient.getDebugId(), payload));
                            bufferFull = !byteBuffer.hasRemaining();
                    }
                } catch (IOException ioe) {
                    // by default the tcpClient gets closed on exceptions, nothing to do here
                    report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                    return;
                }
            }

            // buffer was empty and we read something, trigger write (otherwise a write-ready callback must have been set)
            if (bufferInitiallyEmpty) {
                if (useExecutor) {
                    executor.execute(pumpResponse);
                } else {
                    pumpResponse();
                }
            }

            // read some more since buffer has space available for reading
        } while (!bufferFull);
    }

    @Override
    protected void pumpResponse() {
        boolean bufferInitiallyFull;
        synchronized (byteBuffer) {
            int written;
            bufferInitiallyFull = !byteBuffer.hasRemaining();
            byteBuffer.flip();

            report(String.format("%s sending [%s]", tcpClient.getDebugId(),
                    new String(byteBuffer.array(), 0, byteBuffer.limit())));

            try {
                written = tcpClient.getWriteChannel().write(byteBuffer);
            } catch (Exception ioe) {
                echoCounts.failedWrite.incrementAndGet();
                // by default the tcpClient gets closed on exceptions, nothing to do here
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                return;
            }

            report(String.format("%s sent [%s]", tcpClient.getDebugId(),
                    new String(byteBuffer.array(), 0, byteBuffer.position())));
            byteBuffer.compact();

            try {
                if (byteBuffer.position() != 0) { // still some bytes left to be written
                    tcpClient.getWriteChannel().addChannelReadyCallback(pumpResponse);
                }

                if (written == 0) {
                    return;
                } else if (readEos) {
                    // by default the tcpClient gets closed on both eos, nothing to do here
                    shutdownOutput();
                    return;
                }
            } catch (Exception ioe) {
                // add counter
                // by default the tcpClient gets closed on exceptions, nothing to do here
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                return;
            }
        }

        if (bufferInitiallyFull) {
            // the buffer was full when we started and some bytes were written and readEos is false -- read some more
            pumpRequest();
        }
    }
}