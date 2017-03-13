package org.rouplex.commons.collections;

import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class SortedByValueMapTest {

    @Test
    public void testOrdering() throws Exception {
        SortedByValueMap<String, Integer> sortedByValueMap = new SortedByValueMap();
        Random random = new Random();

        for (int i = 0; i < 1000; i++) {
            sortedByValueMap.put(UUID.randomUUID().toString(), random.nextInt(10));
        }

        int current = -1;
        for (Map.Entry<String, Integer> entry : sortedByValueMap.sortedByValue()) {
            //System.out.println(entry.getKey() + ": " + entry.getValue());
            Assert.assertTrue(entry.getValue() >= current);
            current = entry.getValue();
        }
    }

    @Test
    public void testRemove() throws Exception {
        SortedByValueMap<String, Integer> sortedByValueMap = new SortedByValueMap();
        Random random = new Random();

        for (int i = 0; i < 1000; i++) {
            sortedByValueMap.put(UUID.randomUUID().toString(), random.nextInt(10));
        }

        for (Iterator<Map.Entry<String, Integer>> iterator = sortedByValueMap.sortedByValue().iterator(); iterator.hasNext();) {
            Map.Entry<String, Integer> entry = iterator.next();
            //System.out.println(entry.getKey() + ": " + entry.getValue());
            iterator.remove();
        }

        for (Iterator<Map.Entry<String, Integer>> iterator = sortedByValueMap.sortedByValue().iterator(); iterator.hasNext();) {
            Assert.fail("There should be no remeining elements in the maps since we removed each one of them");
        }
    }
}