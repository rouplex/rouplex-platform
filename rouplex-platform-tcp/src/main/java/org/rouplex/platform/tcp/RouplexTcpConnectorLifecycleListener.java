package org.rouplex.platform.tcp;

/**
 * A rouplexTcpConnector lifecycle listener
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RouplexTcpConnectorLifecycleListener<T extends RouplexTcpHub> {
    /**
     * A rouplexTcpConnector was created (connected/bound depending on context)
     *
     * @param rouplexTcpConnector
     *          The rouplexTcpConnector object
     */
    void onCreated(T rouplexTcpConnector);

    /**
     * A rouplexTcpConnector was destroyed (closed/connected/stopped depending on context)
     *
     * @param rouplexTcpConnector
     *          The rouplexTcpConnector object that was destroyed
     * @param drainedChannels
     *          true if the channels were properly drained (input has read EOS and output has written EOS)
     */
    void onDestroyed(T rouplexTcpConnector, boolean drainedChannels);
}
