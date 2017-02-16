package org.rouplex.commons.configuration;

/**
 * A listener for configuration changes.
 *
 * A {@link ConfigurationManager} may have merged several {@link Configuration} instances, some of which may have
 * overlapping entries. For the moment, there is no saying as to which config entry wins, and for that reason,
 * we only broadcast the key that has changed, and the callee must perform a getConfiguration() in order to getConfiguration
 * the new value, which simetimes could result to be the same as the old.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface ConfigurationListener {
    void onConfigurationUpdate(Enum key);
}
