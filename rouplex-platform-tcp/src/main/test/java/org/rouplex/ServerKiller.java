package org.rouplex;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */

import org.rouplex.platform.rr.NotificationListener;
import org.rouplex.platform.tcp.RouplexTcpBinder;
import org.rouplex.platform.tcp.RouplexTcpClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;

public class ServerKiller {
    public static void main(String args[]) throws Exception {
        RouplexTcpBinder rouplexBinder = null;
        RouplexTcpClient rouplexTcpClient = null;

        try {
            rouplexBinder = new RouplexTcpBinder(Selector.open(), null);
            rouplexBinder.setRouplexTcpClientConnectedListener(new NotificationListener<RouplexTcpClient>() {
                @Override
                public void onEvent(RouplexTcpClient rouplexTcpClient) {
                    try {
                        rouplexTcpClient.hookSendChannel(null).send(ByteBuffer.allocate(0)); // send EOS
                    } catch (IOException ioe) {
                        // handle it
                    }
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
