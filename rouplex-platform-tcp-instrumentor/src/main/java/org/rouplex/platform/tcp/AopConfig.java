package org.rouplex.platform.tcp;

import java.lang.reflect.Field;
import java.util.Properties;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class AopConfig {
    boolean aggregateLocalAddresses;
    boolean aggregateLocalPorts;
    boolean aggregateRemoteAddresses;
    boolean aggregateRemotePorts;

    boolean aggregateTcpBinders;

    AopConfig() {
    }

    AopConfig(Properties properties) {
        for (Field field : AopConfig.class.getDeclaredFields()) {
            try {
                field.set(this, properties.get(field.getName()));
            } catch (Exception e) {
            }
        }
    }

    public static AopConfig fromSystemProperties() {
        return new AopConfig(System.getProperties());
    }

    public static AopConfig allAggregated() {
        AopConfig aopConfig = new AopConfig();
        aopConfig.aggregateLocalAddresses = true;
        aopConfig.aggregateLocalPorts = true;
        aopConfig.aggregateRemoteAddresses = true;
        aopConfig.aggregateRemotePorts = true;
        aopConfig.aggregateTcpBinders = true;

        return aopConfig;
    }
}
