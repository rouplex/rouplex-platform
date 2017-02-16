package org.rouplex.platform;

import org.rouplex.common.configuration.Configuration;
import org.rouplex.common.configuration.ConfigurationProvider;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class CorePlatform {
    private static final Configuration defaultConfiguration = new ConfigurationProvider() {{
        putConfigurationEntry(PlatformConfiguration.Keys.listenPort, "8088");
        putConfigurationEntry(PlatformConfiguration.Keys.protocolScheme, PlatformConfiguration.ProtocolSchemes.sharedMemory.toString());
        putConfigurationEntry(PlatformConfiguration.Keys.serializationType, PlatformConfiguration.SerializationTypes.json.toString());
        putConfigurationEntry(PlatformConfiguration.Keys.routingStrategy, PlatformConfiguration.RoutingStrategies.roundRobin.toString());
    }}.get();

    private final Configuration configuration;

    public CorePlatform() {
        this(defaultConfiguration);
    }

    public CorePlatform(Configuration configuration) {
        this.configuration = configuration;
    }

    public Connector getConnector() {
        return getConnector(configuration);
    }

    public Connector getConnector(Configuration configuration) {
        return null;
    }
}
