package org.rouplex.platform.tcp;

/**
 * A rouplexTcpClient lifecycle listener.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RouplexTcpClientListener {
    /**
     * A rouplexTcpClient was connected.
     *
     * @param rouplexTcpClient
     *          the rouplexTcpClient object
     */
    void onConnected(RouplexTcpClient rouplexTcpClient);

    /**
     * A rouplexTcpClient failed connection.
     *
     * @param rouplexTcpClient
     *          the rouplexTcpClient object
     * @param reason
     *          the reason for which rouplexTcpClient failed connection
     */
    void onConnectionFailed(RouplexTcpClient rouplexTcpClient, Exception reason);

    /**
     * A rouplexTcpClient was disconnected.
     *
     * @param rouplexTcpClient
     *          the rouplexTcpClient object
     * @param optionalReason
     *          the reason for which rouplexTcpClient disconnected, or null if this is the result of a user request
     * @param drainedChannels
     *          true if the channels were properly drained (input has read EOS and output has written EOS)
     */
    void onDisconnected(RouplexTcpClient rouplexTcpClient, Exception optionalReason, boolean drainedChannels);
}
