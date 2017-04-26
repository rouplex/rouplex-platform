package org.rouplex.commons.configuration;

/**
 * A listener for configuration updates.
 *
 * A {@link ConfigurationManager} may have merged several {@link Configuration} instances, some of which may have
 * overlapping entries. For the moment, there is no saying as to which config entry wins, and for that reason,
 * we only broadcast the key that has changed, and the callee must perform a getConfiguration() in order to get the
 * entry's new value, which sometimes could happen to be the same as the old.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ConfigurationUpdateListener {
    void onConfigurationUpdate(Enum key);
}
