package org.rouplex.platform.tcp;

/**
 * A rouplexTcpServer lifecycle listener
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RouplexTcpServerListener {
    /**
     * A rouplexTcpServer was bound and is listening for connections
     *
     * @param rouplexTcpServer
     *          The rouplexTcpServer object
     */
    void onBound(RouplexTcpServer rouplexTcpServer);

    /**
     * A rouplexTcpServer failed binding. Not used for now since a server is created synchronously and an IOException
     * will be thrown if there are any exceptions (and the RouplexTcpServer will not be instantiated)
     *
     * @param rouplexTcpServer
     *          The rouplexTcpServer object
     * @param reason
     *          The reason for which rouplexTcpServer failed binding
     */
    void onBindFailed(RouplexTcpServer rouplexTcpServer, Exception reason);

    /**
     * A rouplexTcpServer was unbound
     *
     * @param rouplexTcpServer
     *          The rouplexTcpServer object
     */
    void onUnBound(RouplexTcpServer rouplexTcpServer);
}
