package org.rouplex.common.configuration;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A configuration structure aiming to force documentation by use of enum keys,
 * and stay generic enough to be shared across unrelated components.
 *
 * A configuration instance can only be obtained and updated via a {@link ConfigurationProvider} instance.
 * The configuration instance offers the hooks to listen for related changes.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Configuration {
    final Map<Enum, String> keyValues;
    private final Set<ConfigurationListener> listeners = new HashSet<ConfigurationListener>();

    Configuration(Map<Enum, String> keyValues) {
        this.keyValues = keyValues;
    }

    public String get(Enum key) {
        return keyValues.get(key);
    }

    public Closeable addListener(final ConfigurationListener listener) {
        listeners.add(listener);

        return new Closeable() {
            @Override
            public void close() throws IOException {
                listeners.remove(listener);
            }
        };
    }

    void notifyChange(Enum key, String oldValue, String newValue) {
        for (ConfigurationListener listener : listeners) {
            try {
                listener.onConfigurationUpdate(key, oldValue, newValue, this);
            } catch (Exception e) {
                // just swallow for now
            }
        }
    }
}
