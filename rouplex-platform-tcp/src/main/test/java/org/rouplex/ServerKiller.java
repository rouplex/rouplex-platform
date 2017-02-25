package org.rouplex;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */

import org.rouplex.platform.rr.EventListener;
import org.rouplex.platform.tcp.RouplexTcpBroker;
import org.rouplex.platform.tcp.RouplexTcpClient;

import java.io.IOException;
import java.nio.channels.Selector;

public class ServerKiller {
    public static void main(String args[]) throws Exception {
        RouplexTcpBroker rouplexBroker = null;
        RouplexTcpClient rouplexTcpClient = null;

        try {
            rouplexBroker = new RouplexTcpBroker(Selector.open(), null);
            rouplexBroker.setTcpClientAddedListener(new EventListener<RouplexTcpClient>() {
                @Override
                public void onEvent(RouplexTcpClient rouplexTcpClient) {
                    rouplexTcpClient.hookSendChannel(null).send(null); // send EOS
                }
            });

            rouplexTcpClient = RouplexTcpClient.newBuilder()
                    .withRouplexBroker(rouplexBroker)
                    .withRemoteAddress("10.0.0.159", 9999)
                    .build();
        } finally {
            if (rouplexTcpClient != null) {
                try {
                    rouplexTcpClient.close();
                } catch (IOException e) {
                }
            }

            if (rouplexBroker != null) {
                try {
                    rouplexBroker.close();
                } catch (IOException e) {
                }
            }
        }
    }
}
