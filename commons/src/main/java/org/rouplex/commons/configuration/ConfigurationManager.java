package org.rouplex.commons.configuration;

import java.io.Closeable;
import java.io.IOException;

/**
 * A configuration manager structure offering a handful of calls to set up and update a {@link Configuration} instance.
 * This structure can be subclassed with more functionality for serializing/deserializing the configurations.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ConfigurationManager implements Closeable {
    private final Configuration configuration = new Configuration();

    /**
     * Put a configuration entry, possibly replacing an old one.
     *
     * Setting the value to null is not the same as removing the entry altogether.
     * If upstream configurations contain the same entry, their value will be overridden by this one.
     *
     * @param key the key for the entry to be added or replaced
     * @param value the value of the entry
     */
    public void putConfigurationEntry(Enum key, String value) {
        configuration.putConfigurationEntry(key, value);
    }

    /**
     * Remove a configuration entry.
     *
     * If upstream configurations contain the same entry, their value will become visible now. If more than one upstream
     * configuration contains the same entry, it is unspecified which one will win.
     *
     * @param key the key for the entry to be removed
     */
    public void removeConfigurationEntry(Enum key) {
        configuration.removeConfigurationEntry(key);
    }

    /**
     * Merge an upstream configuration.
     *
     * Entries of the upstream configuration will become visible via the configuration, unless the configuration
     * contains an entry with the same key. Events related to upstream updates will be forwarded to listeners
     * of this configuration.
     *
     * @param configuration
     */
    public void mergeConfiguration(Configuration configuration) {
        this.configuration.mergeConfiguration(configuration);
    }

    /**
     * Get the {@link Configuration} instance which can be used to access values created or updated via this class.
     *
     * @return the related {@link Configuration} instance
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void close() throws IOException {
        configuration.close();
    }
}
