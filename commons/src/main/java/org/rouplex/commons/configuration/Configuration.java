package org.rouplex.commons.configuration;

import org.rouplex.commons.Objects;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;

/**
 * A configuration structure aiming to force documentation by use of enum keys,
 * and stay generic enough to be shared across unrelated components.
 *
 * A configuration instance can only be obtained and updated via a {@link ConfigurationManager} instance.
 * The configuration instance offers the hooks for listeners of configuration changes.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Configuration {
    private final Map<Enum, String> keyValues = new HashMap<Enum, String>();
    private final LinkedHashMap<Configuration, Closeable> mergedConfigurations = new LinkedHashMap<Configuration, Closeable>();
    private final Set<ConfigurationListener> listeners = new HashSet<ConfigurationListener>();

    private final ConfigurationListener configurationBroadcaster = new ConfigurationListener() {
        @Override
        public void onConfigurationUpdate(Enum key) {
            for (ConfigurationListener listener : listeners) {
                try {
                    listener.onConfigurationUpdate(key);
                } catch (Exception e) {
                    // just swallow for now
                }
            }
        }
    };

    /**
     * Get the value associated to a key on this configuration (or on upstream/merged  instances)
     * This configuration instance is searched first. If found, its entry value is returned,
     * otherwise the upstream {@link Configuration}s are searched, the order is unspecified.
     *
     * @param key the key to be searched for
     * @return the value found
     * @throws NoSuchElementException if no entry with that key is found
     */
    public String get(Enum key) throws NoSuchElementException {
        synchronized (keyValues) {
            if (keyValues.containsKey(key)) {
                return keyValues.get(key);
            }
        }

        for (Configuration configuration : mergedConfigurations.keySet()) {
            synchronized (configuration.keyValues) {
                if (configuration.keyValues.containsKey(key))
                    return configuration.keyValues.get(key);
            }
        }

        throw new NoSuchElementException(String.format("Configuration key %s not found", key));
    }

    public String getString(Enum key, String orElse) {
        try {
            return get(key);
        } catch (NoSuchElementException e) {
            return orElse;
        }
    }

    public boolean getBoolean(Enum key) throws NoSuchElementException {
        return Boolean.parseBoolean(get(key));
    }

    public boolean getBoolean(Enum key, boolean orElse) {
        try {
            return getBoolean(key);
        } catch (RuntimeException re) {
            return orElse;
        }
    }

    public int getInteger(Enum key) throws NoSuchElementException {
        return Integer.parseInt(get(key));
    }

    public int getInteger(Enum key, int orElse) {
        try {
            return getInteger(key);
        } catch (RuntimeException re) {
            return orElse;
        }
    }

    /**
     * Add a new listener.
     *
     * @param listener the new listener. If the listener has been added in the past, this becomes a noop.
     * @return a {@link Closeable} instance which can be used to remove the listener at some later point.
     */
    public Closeable addListener(final ConfigurationListener listener) {
        listeners.add(listener);

        return new Closeable() {
            @Override
            public void close() throws IOException {
                listeners.remove(listener);
            }
        };
    }

    /**
     * Remove the listeners that this configuration has put in the upstream configurations to prevent memory leaks.
     *
     * @throws IOException
     */
    void close() throws IOException {
        IOException firstCaught = null;

        for (Closeable closeable : mergedConfigurations.values()) {
            try {
                closeable.close();
            } catch (IOException e) {
                if (firstCaught == null) {
                    firstCaught = e;
                }
            }
        }

        if (firstCaught != null) {
            throw firstCaught;
        }
    }

    /**
     * Put a configuration entry, possibly replacing an old one.
     *
     * Setting the value to null is not the same as removing the entry altogether.
     * If upstream configurations contain the same entry, their value will be overridden by this one.
     *
     * This method is package protected to allow access from {@link ConfigurationManager} but it is not public so that
     * instances of the class can be referenced without mutability concerns.
     *
     * @param key the key for the entry to be added or replaced
     * @param value the value of the entry
     */
    void putConfigurationEntry(Enum key, String value) {
        String oldValue;
        synchronized (keyValues) {
            oldValue = keyValues.put(key, value);
        }

        if (!Objects.areEqual(oldValue, value)) {
            configurationBroadcaster.onConfigurationUpdate(key);
        }
    }

    /**
     * Remove a configuration entry.
     *
     * If upstream configurations contain the same entry, their value will become visible now. If more than one upstream
     * configuration contains the same entry, it is unspecified which one will win.
     *
     * This method is package protected to allow access from {@link ConfigurationManager} but it is not public so that
     * instances of the class can be referenced without mutability concerns.
     *
     * @param key the key for the entry to be removed
     */
    void removeConfigurationEntry(Enum key) {
        synchronized (keyValues) {
            keyValues.remove(key);
        }

        configurationBroadcaster.onConfigurationUpdate(key);
    }

    /**
     * Merge an upstream configuration.
     *
     * Entries of the upstream configuration will become visible via this configuration, unless this configuration
     * contains an entry with the same key. Events related to upstream updates will be forwarded to listeners
     * of this configuration.
     *
     * @param configuration
     */
    void mergeConfiguration(Configuration configuration) {
        mergedConfigurations.put(configuration, configuration.addListener(configurationBroadcaster));
    }
}
