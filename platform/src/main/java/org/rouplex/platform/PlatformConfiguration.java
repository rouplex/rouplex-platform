package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class PlatformConfiguration {
    public enum Keys {
        listenPort,
        protocolScheme,
        serializationType,
        routingStrategy
    }

    public enum ProtocolSchemes {
        sharedMemory,
        http
    }

    public enum SerializationTypes {
        json
    }

    public enum RoutingStrategies {
        roundRobin
    }
}
