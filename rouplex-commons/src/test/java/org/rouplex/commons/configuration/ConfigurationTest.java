package org.rouplex.commons.configuration;

import org.junit.Assert;
import org.junit.Test;

import java.io.Closeable;
import java.util.*;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ConfigurationTest {
    enum SpecificConfigurationKeys {
        specificKey1,
        specificKey2
    }

    enum MoreConfigurationKeys{
        anotherKey,
    }

    ConfigurationManager configurationManager = new ConfigurationManager();
    Configuration configuration = configurationManager.getConfiguration();

    class MyConfigurationUpdateListener implements ConfigurationUpdateListener {
        List<Map.Entry<Enum, String>> values = new ArrayList<Map.Entry<Enum, String>>();

        @Override
        public void onConfigurationUpdate(Enum key) {
            try {
                values.add(new AbstractMap.SimpleEntry<Enum, String>(key, configuration.get(key)));
            } catch (NoSuchElementException e) {
                values.add(new AbstractMap.SimpleEntry<Enum, String>(key, "NoSuchElementException"));
            }
        }
    }

    @Test
    public void test() throws Exception {
        MyConfigurationUpdateListener myConfigurationListener = new MyConfigurationUpdateListener();
        try {
            configuration.get(SpecificConfigurationKeys.specificKey1);
            throw new Exception("A NoSuchElementException should have been thrown but it wasn't");
        } catch (NoSuchElementException e) {
            // expected
        }

        configurationManager.putConfigurationEntry(SpecificConfigurationKeys.specificKey1, "specificKey1");
        Assert.assertEquals("specificKey1", configuration.get(SpecificConfigurationKeys.specificKey1));

        Closeable closeable = configuration.addListener(myConfigurationListener);
        Assert.assertEquals(0, myConfigurationListener.values.size());

        configurationManager.putConfigurationEntry(SpecificConfigurationKeys.specificKey2, "specificKey2");
        Assert.assertEquals(1, myConfigurationListener.values.size());
        Assert.assertEquals(SpecificConfigurationKeys.specificKey2, myConfigurationListener.values.get(0).getKey());
        Assert.assertEquals("specificKey2", myConfigurationListener.values.get(0).getValue());

        configurationManager.putConfigurationEntry(MoreConfigurationKeys.anotherKey, "anotherKey");
        Assert.assertEquals(2, myConfigurationListener.values.size());
        Assert.assertEquals(MoreConfigurationKeys.anotherKey, myConfigurationListener.values.get(1).getKey());
        Assert.assertEquals("anotherKey", myConfigurationListener.values.get(1).getValue());

        configurationManager.putConfigurationEntry(SpecificConfigurationKeys.specificKey2, "specificKey2Updated");
        Assert.assertEquals(3, myConfigurationListener.values.size());
        Assert.assertEquals(SpecificConfigurationKeys.specificKey2, myConfigurationListener.values.get(2).getKey());
        Assert.assertEquals("specificKey2Updated", myConfigurationListener.values.get(2).getValue());

        configurationManager.removeConfigurationEntry(SpecificConfigurationKeys.specificKey2);
        Assert.assertEquals(4, myConfigurationListener.values.size());
        Assert.assertEquals(SpecificConfigurationKeys.specificKey2, myConfigurationListener.values.get(3).getKey());
        Assert.assertEquals("NoSuchElementException", myConfigurationListener.values.get(3).getValue());

        closeable.close();
        configurationManager.removeConfigurationEntry(MoreConfigurationKeys.anotherKey);
        Assert.assertEquals(4, myConfigurationListener.values.size());
    }
}