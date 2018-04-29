package org.rouplex.platform.tcp;

/**
 * A {@link TcpServer} lifecycle listener.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface TcpServerLifecycleListener {
    /**
     * A tcpServer was just bound and will be accepting new {@link TcpClient} connections.
     *
     * @param tcpServer
     *          the tcpServer that just got bound.
     */
    void onBound(TcpServer tcpServer);

    /**
     * A tcpServer failed to bind to local address and start listening.
     *
     * @param tcpServer
     *          the tcpServer that failed to bind.
     * @param reason
     *          the reason for which tcpClient failed connection.
     */
    void onBindFailed(TcpServer tcpServer, Exception reason);

    /**
     * A tcpServer was just unbound and will not be accepting new {@link TcpClient} connections.
     * The already obtained tcpClients are not effected in any way by this server state change.
     *
     * @param tcpServer
     *          the tcpServer that got unbound.
     * @param optionalReason
     *          the reason for unbinding of the server or null if this is just the result of a client
     *          call to {@link TcpServer#close()}.
     */
    void onUnbound(TcpServer tcpServer, Exception optionalReason);
}
