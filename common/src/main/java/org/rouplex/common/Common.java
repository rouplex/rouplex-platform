package org.rouplex.common;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Common {
    public static boolean areEqual(Object obj1, Object obj2) {
        return obj1 == null ? obj2 == null : obj1.equals(obj2);
    }
}
