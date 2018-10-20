package org.rouplex.platform.tcp;

/**
 * A {@link TcpClient} lifecycle listener.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface TcpClientListener {
    /**
     * A client was connected.
     *
     * @param client
     *          The client that got connected.
     */
    void onConnected(TcpClient client);

    /**
     * A client failed connection.
     *
     * @param client
     *          The client that failed to connect.
     * @param reason
     *          The reason for which client failed connection.
     */
    void onConnectionFailed(TcpClient client, Exception reason);

    /**
     * A client was disconnected.
     *
     * @param client
     *          The client that got disconnected
     * @param optionalReason
     *          The reason for disconnection of the client or null if this is just the result of a client
     *          call to {@link TcpClient#close()}.
     */
    void onDisconnected(TcpClient client, Exception optionalReason);
}
