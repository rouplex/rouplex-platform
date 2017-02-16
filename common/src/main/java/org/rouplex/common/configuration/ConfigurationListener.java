package org.rouplex.common.configuration;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ConfigurationListener {
    void onConfigurationUpdate(Enum key, String oldValue, String newValue, Configuration configuration);
}
