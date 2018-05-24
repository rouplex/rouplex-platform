package org.rouplex.platform.tcp;

/**
 * A {@link TcpClient} lifecycle listener.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface TcpClientLifecycleListener {
    /**
     * A tcpClient was connected.
     *
     * @param tcpClient
     *          The tcpClient that got connected.
     */
    void onConnected(TcpClient tcpClient);

    /**
     * A tcpClient failed connection.
     *
     * @param tcpClient
     *          The tcpClient that failed to connect.
     * @param reason
     *          The reason for which tcpClient failed connection.
     */
    void onConnectionFailed(TcpClient tcpClient, Exception reason);

    /**
     * A tcpClient was disconnected.
     *
     * @param tcpClient
     *          The tcpClient that got disconnected
     * @param optionalReason
     *          The reason for disconnection of the client or null if this is just the result of a client
     *          call to {@link TcpClient#close()}.
     */
    void onDisconnected(TcpClient tcpClient, Exception optionalReason);
}
