package org.rouplex.jaxrs.client;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class Rouplexion {
    private final Class clazz;

    Rouplexion(Class clazz) {
        this.clazz = clazz;
    }

    static Set<Annotation> getAnnotations(Method method, boolean includeSuperClasses, boolean includeInterfaces) {
//        POST[] anns = method.getAnnotationsByType(POST.class);
        Class c;
        return null;
    }

    static Set<Annotation> getClassAnnotations(Class<?> clazz) {
        Annotation[] anns = clazz.getDeclaredAnnotations();
        Kot p = clazz.getAnnotation(Kot.class);
        p.hic();
        Class c;
        return null;
    }

    public static void main(String[] kot) throws Exception {
        getClassAnnotations(JaxrsTest2.class);
        getAnnotations(JaxrsTest.class.getMethod("ping", String.class), true, true);
    }
}
