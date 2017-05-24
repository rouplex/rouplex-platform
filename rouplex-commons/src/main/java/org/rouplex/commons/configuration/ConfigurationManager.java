package org.rouplex.commons.configuration;

import org.rouplex.commons.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;

/**
 * A configuration manager offers a handful of calls to set up and update a {@link Configuration} instance, which in
 * turn provides a view over such entries. ConfigurationManager provides the means to add or remove configuration
 * entries, as well as merge in all the entries coming from another Configuration instance. In the later scenario, the
 * merged entries are only viewable if there are no entries of the same key set via this ConfigurationManager instance.
 *
 * This structure can be subclassed with more functionality for serializing/deserializing the configurations.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ConfigurationManager implements Closeable {
    private final Configuration configuration = new Configuration();

    /**
     * Put a configuration entry, possibly replacing an old or inherited entry. If upstream configurations contain the
     * same entry, their value will be overridden by this one.
     *
     * @param key
     *          the key for the entry to be added or replaced
     * @param value
     *          the value of the entry
     */
    public void putConfigurationEntry(Enum key, @Nullable String value) {
        configuration.putConfigurationEntry(key, value);
    }

    /**
     * Remove a configuration entry.
     *
     * If upstream configurations contain the same entry, their value will become visible now. If more than one upstream
     * configuration contains the same entry, it is unspecified which one will win.
     *
     * @param key
     *          the key for the entry to be removed
     */
    public void removeConfigurationEntry(Enum key) {
        configuration.removeConfigurationEntry(key);
    }

    /**
     * Merge an upstream configuration.
     *
     * If the upstream configuration has an entry that is not set via this configuration instance, that entry will
     * become visible via the associated {@link Configuration} instance. If a previously merged Configuration has the
     * same entry set, it is undefined which particular entry will be returned by the Configuration instance.
     *
     * Events related to upstream updates will be forwarded to listeners of this configuration as well.
     *
     * @param configuration
     *          the configuration to be merged in
     */
    public void mergeConfiguration(Configuration configuration) {
        this.configuration.mergeConfiguration(configuration);
    }

    /**
     * Get the {@link Configuration} instance which can be used to access values created or updated via this class.
     *
     * @return
     *          the related {@link Configuration} instance
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void close() throws IOException {
        configuration.close();
    }
}
