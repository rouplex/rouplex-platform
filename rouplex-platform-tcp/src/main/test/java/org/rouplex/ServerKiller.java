package org.rouplex;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */

import org.rouplex.platform.rr.EventListener;
import org.rouplex.platform.tcp.RouplexTcpBinder;
import org.rouplex.platform.tcp.RouplexTcpClient;

import java.io.IOException;
import java.nio.channels.Selector;

public class ServerKiller {
    public static void main(String args[]) throws Exception {
        RouplexTcpBinder rouplexBinder = null;
        RouplexTcpClient rouplexTcpClient = null;

        try {
            rouplexBinder = new RouplexTcpBinder(Selector.open(), null);
            rouplexBinder.setTcpClientAddedListener(new EventListener<RouplexTcpClient>() {
                @Override
                public void onEvent(RouplexTcpClient rouplexTcpClient) {
                    rouplexTcpClient.hookSendChannel(null).send(null); // send EOS
                }
            });

            rouplexTcpClient = RouplexTcpClient.newBuilder()
                    .withRouplexTcpBinder(rouplexBinder)
                    .withRemoteAddress("10.0.0.159", 9999)
                    .build();
        } finally {
            if (rouplexTcpClient != null) {
                try {
                    rouplexTcpClient.close();
                } catch (IOException e) {
                }
            }

            if (rouplexBinder != null) {
                rouplexBinder.close();
            }
        }
    }
}
