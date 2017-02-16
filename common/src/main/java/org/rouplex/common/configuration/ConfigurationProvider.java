package org.rouplex.common.configuration;

import org.rouplex.common.Common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A configuration provider structure offering a handful of calls to set up and update  a {@link Configuration} instance.
 * This structure can be subclassed with more functionality for serializing/deserializing the configurations.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ConfigurationProvider {
    private final Map<Enum, String> keyValues = new ConcurrentHashMap<Enum, String>();
    private final Configuration configuration = new Configuration(keyValues);

    public void putConfigurationEntry(Enum key, String value) {
        String oldValue = keyValues.put(key, value);

        if (!Common.areEqual(oldValue, value)) {
            configuration.notifyChange(key, oldValue, value);
        }
    }

    public void removeConfigurationEntry(Enum key) {
        String oldValue = keyValues.remove(key);
        configuration.notifyChange(key, oldValue, null);
    }

    public void mergeConfiguration(Configuration configuration) {
        for (Map.Entry<Enum, String> entry : configuration.keyValues.entrySet()) {
            putConfigurationEntry(entry.getKey(), entry.getValue());
        }
    }

    public Configuration get() {
        return configuration;
    }
}
