package org.rouplex.platform.protocols;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class SharedMemory {
    Queue<byte[]> incomingRequests = new ArrayBlockingQueue<byte[]>(1);
}
