package org.rouplex.platform;

import org.rouplex.commons.configuration.Configuration;
import org.rouplex.commons.configuration.ConfigurationManager;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexPlatform {
    private final ConfigurationManager configurationManager = new ConfigurationManager();

    public Configuration getConfiguration() {
        return configurationManager.getConfiguration();
    }
}
