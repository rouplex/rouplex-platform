package org.rouplex.commons.reflections;

import org.junit.Assert;
import org.junit.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Collection;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexReflectionsTest {

    @Retention(value=RUNTIME)
    @Target(value={TYPE, METHOD})
    public @interface Annotation1 {
        String param1() default "ann11";
        int param2() default 12;
    }

    @Retention(value=RUNTIME)
    @Target(value={TYPE, METHOD})
    public @interface Annotation2 {
        String param1() default "ann21";
        int param2() default 22;
    }

    @Retention(value=RUNTIME)
    @Target(value={TYPE, METHOD})
    public @interface Annotation3 {
        String param1() default "ann31";
        int param2() default 32;
    }

    @Annotation1
    interface Interface1 {
        void call1();
    }

    @Annotation1
    @Annotation2
    interface Interface2 {
        int call2();
    }

    @Annotation3
    interface Interface3 extends Interface2 {
        int call3(String param3);
    }

    abstract class Class1 implements Interface1 {
        public abstract void call0();
    }

    @Annotation2
    class Class2 extends Class1 implements Interface2 {

        @Override
        public void call0() {
        }

        @Override
        public void call1() {

        }

        @Override
        public int call2() {
            return 0;
        }
    }

    @Annotation1(param1="param1")
    @Annotation2
    @Annotation3
    class Class3 extends Class2 implements Interface1, Interface3 {

        @Override
        public int call3(String param3) {
            return 0;
        }
    }

    @Test
    public void testGetClassHierarchyCollection() {
        Collection<Class> classHierarchy = RouplexReflections.getClassHierarchyCollection(Class3.class);
        Assert.assertEquals(7, classHierarchy.size());
    }

    @Test
    public void testGetAnnotationsCollection() {
        Collection<Annotation> annotations = RouplexReflections.getAllAnnotationsCollection(Class3.class);
        for (Annotation a : annotations) {
            System.out.println(a.toString());
        }

        Class clazz = Annotation3.class;

        Assert.assertEquals(8, annotations.size());
    }

    @Test
    public void testGetPublicAbstractInstanceMethods() {
        Collection<Method> methods = RouplexReflections.getPublicAbstractInstanceMethods(Class3.class);
        for (Method method : methods) {
            System.out.println(method.toString());
        }

        Assert.assertEquals(4, methods.size());
    }
}
