package org.rouplex.commons.collections;

import org.junit.Test;

import java.util.Map;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class SortedByValueMapTest {

    @Test
    public void test() throws Exception {
        SortedByValueMap<String, Integer> sortedByValueMap = new SortedByValueMap();

        sortedByValueMap.put("kot", 1);
        sortedByValueMap.put("plot", 2);
        sortedByValueMap.put("hic", 1);
        sortedByValueMap.put("pic", 2);

        for (Map.Entry<String, Integer> entry : sortedByValueMap.sortedByValue()) {
            //System.out.println(entry.getKey() + ": " + entry.getValue());
            if (entry.getKey().equals("hic")) {
                sortedByValueMap.remove("hic");
            }
        }
    }
}