package org.rouplex.platform;

import org.rouplex.common.configuration.Configuration;

import java.io.Closeable;
import java.io.IOException;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Connector implements Closeable {
    private final Configuration configuration;

    Connector(Configuration configuration) {
        this.configuration = configuration;
    }

    public <T> T getClient(Class<T> clazz) {
        return getClient(clazz, configuration);
    }

    public <T> T getClient(Class<T> clazz, Configuration configuration) {
        return null;
    }

    public <T> Closeable addServer(T server) {
        return addServer(server, configuration);
    }

    public <T> Closeable addServer(T server, Configuration configuration) {
        return new Closeable() {
            @Override
            public void close() throws IOException {
                // nothing for now
            }
        };
    }

    @Override
    public void close() throws IOException {

    }
}
