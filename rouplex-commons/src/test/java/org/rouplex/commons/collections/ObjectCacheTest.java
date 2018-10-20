package org.rouplex.commons.collections;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class ObjectCacheTest {
    ObjectCache<String> objectCache = new ObjectCache<String>(100, 10);

    @Test
    public void test1() {
        String str1 = "str1";
        String str2 = "str2";
        objectCache.offer("1", str1);
        objectCache.offer(null, str2);

        Assert.assertTrue(str1.equals(objectCache.poll("1")));
        Assert.assertTrue(str2.equals(objectCache.poll(null)));
    }
}