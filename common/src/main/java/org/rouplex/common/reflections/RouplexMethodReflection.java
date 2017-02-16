package org.rouplex.common.reflections;

import java.lang.reflect.Method;
import java.util.*;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexMethodReflection implements Comparator<Method> {
    Method method;

    RouplexMethodReflection(Method method) {
        this.method = method;

        Object kot = get(new TreeSet(), new HashSet());
    }

    @Override
    public int compare(Method first, Method second) {
        return 0;
    }

    Object get(Set oo1, Set oo2) {
        return null;
    }

    String get(SortedSet kk1, SortedSet kk2) {
        return "sdf";
    }
}
