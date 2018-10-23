package org.rouplex;

import org.rouplex.platform.tcp.TcpClient;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Echoer {
    private final TcpClient tcpClient;
    private final Counts counts;

    Echoer(TcpClient tcpClient, Counts counts) {
        this.tcpClient = tcpClient;
        this.counts = counts;
    }

    void read(final boolean thenWrite, final boolean thenClose) {
        ByteBuffer bb = ByteBuffer.allocate(10000);

        try {
            int read = tcpClient.getReadChannel().read(bb);
            switch (read) {
                case -1:
                    counts.receivedEos.incrementAndGet();
                    report(String.format("%s received eos", tcpClient.getDebugId()));
                    write(null, false, thenClose);
                    return;
                case 0:
                    tcpClient.getReadChannel().addChannelReadyCallback(new Runnable() {
                        @Override
                        public void run() {
                            read(thenWrite, thenClose);
                        }
                    });
                    break;
                default:
                    bb.flip();
                    String payload = new String(bb.array(), 0, bb.limit());
                    report(String.format("%s received [%s]", tcpClient.getDebugId(), payload));
                    if (thenWrite) {
                        write(bb, false, false);
                    }

                    read(thenWrite, thenClose);
            }
        } catch (IOException ioe) {
            counts.failedRead.incrementAndGet();
            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
        }
    }

    void write(final ByteBuffer bb, final boolean thenShutdown, final boolean thenClose) {
        if (bb != null && bb.hasRemaining()) {
            int position = bb.position();

            report(String.format("%s sending [%s]", tcpClient.getDebugId(),
                    new String(bb.array(), position, bb.limit() - position)));

            try {
                tcpClient.getWriteChannel().write(bb);
            } catch (Exception ioe) {
                counts.failedWrite.incrementAndGet();
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                return;
            }

            report(String.format("%s sent [%s]", tcpClient.getDebugId(),
                    new String(bb.array(), position, bb.position() - position)));

            try {
                if (bb.hasRemaining()) {
                    tcpClient.getWriteChannel().addChannelReadyCallback(new Runnable() {
                        @Override
                        public void run() {
                            write(bb, thenShutdown, thenClose);
                        }
                    });

                    return;
                }
            } catch (Exception ioe) {
                // add counter
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                return;
            }

        }

        if (thenShutdown) {
            report(String.format("%s shutting down", tcpClient.getDebugId()));
            counts.sendingEos.incrementAndGet();

            try {
                tcpClient.getWriteChannel().shutdown();
            } catch (Exception ioe) {
                // add counter
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                return;
            }

            counts.sentEos.incrementAndGet();
            report(String.format("%s shutdown", tcpClient.getDebugId()));
        }

        if (thenClose) {
            report(String.format("%s closing", tcpClient.getDebugId()));
            counts.closing.incrementAndGet();

            try {
                tcpClient.close();
            } catch (Exception ioe) {
                // add counter
                report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
                return;
            }

            counts.closed.incrementAndGet();
            report(String.format("%s closed", tcpClient.getDebugId()));
        }
    }

    private static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }
}