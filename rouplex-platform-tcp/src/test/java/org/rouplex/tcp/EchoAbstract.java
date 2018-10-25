package org.rouplex.tcp;

import org.rouplex.platform.tcp.TcpClient;

abstract class EchoAbstract {
    protected final TcpClient tcpClient;
    protected final EchoCounts echoCounts;

    protected final Runnable pumpRequest = new Runnable() {
        @Override
        public void run() {
            pumpRequest();
        }
    };

    protected final Runnable pumpResponse = new Runnable() {
        @Override
        public void run() {
            pumpResponse();
        }
    };

    EchoAbstract(TcpClient tcpClient, EchoCounts echoCounts) {
        this.tcpClient = tcpClient;
        this.echoCounts = echoCounts;
    }

    abstract void start();
    abstract void pumpRequest();
    abstract void pumpResponse();

    protected void shutdownOutput() {
        report(String.format("%s shutting down", tcpClient.getDebugId()));
        echoCounts.sendingEos.incrementAndGet();

        try {
            tcpClient.getWriteChannel().shutdown();
        } catch (Exception ioe) {
            // add counter
            report(String.format("%s threw exception [%s]", tcpClient.getDebugId(), ioe.getMessage()));
            return;
        }

        echoCounts.sentEos.incrementAndGet();
        report(String.format("%s shutdown", tcpClient.getDebugId()));
    }

    protected static void report(String log) {
        System.out.println(String.format("%s %s %s", System.currentTimeMillis(), Thread.currentThread(), log));
    }
}