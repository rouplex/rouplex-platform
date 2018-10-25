package org.rouplex.tcp;

import java.util.concurrent.atomic.AtomicInteger;

public class EchoCounts {
    final AtomicInteger connectedOk = new AtomicInteger();
    final AtomicInteger failedConnect = new AtomicInteger();
    final AtomicInteger failedWrite = new AtomicInteger();
    final AtomicInteger failedRead = new AtomicInteger();
    final AtomicInteger disconnectedOk = new AtomicInteger();
    final AtomicInteger disconnectedKo = new AtomicInteger();
    final AtomicInteger receivedEos = new AtomicInteger();
    final AtomicInteger sendingEos = new AtomicInteger();
    final AtomicInteger sentEos = new AtomicInteger();
    final AtomicInteger closing = new AtomicInteger();
    final AtomicInteger closed = new AtomicInteger();
    final String actor;

    EchoCounts(String actor) {
        this.actor = actor;
    }

    void report() {
        System.out.println(
            "\n" + actor + "-connectedOk:" + connectedOk.get() +
            "\n" + actor + "-failedConnect:" + failedConnect.get() +
            "\n" + actor + "-failedWrite:" + failedWrite.get() +
            "\n" + actor + "-failedRead:" + failedRead.get() +
            "\n" + actor + "-disconnectedOk:" + disconnectedOk.get() +
            "\n" + actor + "-disconnectedKo:" + disconnectedKo.get() +
            "\n" + actor + "-receivedEos:" + receivedEos.get() +
            "\n" + actor + "-sendingEos:" + sendingEos.get() +
            "\n" + actor + "-sentEos:" + sentEos.get() +
            "\n" + actor + "-closing:" + closing.get() +
            "\n" + actor + "-closed:" + closed.get()
        );
    }
}
