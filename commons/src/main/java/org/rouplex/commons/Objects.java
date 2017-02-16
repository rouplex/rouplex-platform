package org.rouplex.commons;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Objects {
    public static boolean areEqual(Object obj1, Object obj2) {
        return obj1 == null ? obj2 == null : obj1.equals(obj2);
    }
}
