package org.rouplex.commons.reflections;

import org.rouplex.commons.Optional;
import org.rouplex.commons.collections.AbstractIterator;
import org.rouplex.commons.collections.RouplexCollections;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexReflections {

    public static Method getDeclaredMethod(Class<?> clazz, Method method) {
        try {
            return clazz.getDeclaredMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            // no problem, return null
            return null;
        }
    }

    public static Iterator<Class> getIteratorOverSupperClasses(Class clazz) {
        return new SuperClassesIterator(clazz);
    }

    public static Collection<Class> getSupperClassesCollection(Class clazz) {
        return RouplexCollections.getCollection(getIteratorOverSupperClasses(clazz));
    }

    public static Iterator<Class> getIteratorOverClassHierarchy(Class clazz) {
        return new ClassHierarchyIterator(getIteratorOverSupperClasses(clazz));
    }

    public static Collection<Class> getClassHierarchyCollection(Class clazz) {
        return RouplexCollections.getCollection(getIteratorOverClassHierarchy(clazz));
    }

    public static Iterator<Annotation> getIteratorOverAllAnnotations(Class clazz) {
        return new AnnotationIterator(clazz);
    }

    public static Collection<Annotation> getAllAnnotationsCollection(Class clazz) {
        return RouplexCollections.getCollection(getIteratorOverAllAnnotations(clazz));
    }

    public static <A extends Annotation> Iterator<A> getIteratorOverAllAnnotationsOfType(Class<?> clazz, Class<A> annotationClass) {
        return new AnnotationFilterIterator(getIteratorOverAllAnnotations(clazz), annotationClass);
    }

    public static <A extends Annotation> Optional<A> getFirstAnnotationOfType(Class<?> clazz, Class<A> annotationClass) {
        Iterator<A> iteratorOverAllAnnotationsOfType = getIteratorOverAllAnnotationsOfType(clazz, annotationClass);
        return iteratorOverAllAnnotationsOfType.hasNext() ? Optional.of(iteratorOverAllAnnotationsOfType.next()) : Optional.<A> empty();
    }

    public static Collection<Method> getPublicAbstractInstanceMethods(Class<?> clazz) {
        Collection<Method> methods = new ArrayList<Method>();

        for (Class klass : getClassHierarchyCollection(clazz)) {
            for (Method method : klass.getDeclaredMethods()) {
                System.out.println(method.toString());
                if ((method.getModifiers() & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == (Modifier.PUBLIC | Modifier.ABSTRACT)) {
                    methods.add(method);
                }
            }
        }

        return methods;
    }

    public static boolean equalParamTypes(Class<?>[] params1, Class<?>[] params2) {
        if (params1.length == params2.length) {
            for (int i = 0; i < params1.length; i++) {
                if (params1[i] != params2[i])
                    return false;
            }
            return true;
        }
        return false;
    }

    public static boolean equalSignatures(Method method1, Method method2) {
        return method1.getName().equals(method2.getName())
                && method1.getReturnType().equals(method2.getReturnType())
                && equalParamTypes(method1.getParameterTypes(), method2.getParameterTypes());
    }

    private abstract static class BaseIterator<T> extends AbstractIterator<T> {
        protected final AtomicReference<T> nextReference = new AtomicReference<T>();

        protected abstract void locateNext();

        @Override
        public boolean hasNext() {
            return nextReference.get() != null;
        }

        @Override
        public T next() {
            synchronized (nextReference) {
                T result = nextReference.get();
                if (result == null) {
                    throw new NoSuchElementException();
                }

                locateNext();
                return result;
            }
        }
    }

    private static class SuperClassesIterator extends BaseIterator<Class> {
        SuperClassesIterator(Class clazz) {
            nextReference.set(clazz);
        }

        @Override
        protected void locateNext() {
            nextReference.set(nextReference.get().getSuperclass());
        }
    }

    private static class ClassHierarchyIterator extends BaseIterator<Class> {
        private final Queue<Class> remainingClasses;
        private final Set<Class> visitedClasses = new HashSet<Class>();

        ClassHierarchyIterator(Iterator<Class> superClasses) {
            remainingClasses = new LinkedList<Class>(RouplexCollections.getCollection(superClasses));

            locateNext();
        }

        @Override
        protected void locateNext() {
            while (!remainingClasses.isEmpty()) {
                Class next = remainingClasses.remove();

                if (visitedClasses.add(next)) {
                    remainingClasses.addAll(Arrays.asList(next.getInterfaces()));
                    nextReference.set(next);
                    return;
                }
            }

            nextReference.set(null);
        }
    }

    private static class AnnotationIterator extends BaseIterator<Annotation> {
        private final Iterator<Class> classHierarchy;
        private final AtomicReference<Iterator<Annotation>> currentElementAnnotations = new AtomicReference<Iterator<Annotation>>(AbstractIterator.<Annotation>getEmptyIterator());
        private final Method method;

        AnnotationIterator(AnnotatedElement annotatedElement) {
            Class clazz = null;

            if (annotatedElement instanceof Class) {
                clazz = (Class) annotatedElement;
            }

            if (annotatedElement instanceof Method) {
                method = (Method) annotatedElement;
                clazz = method.getDeclaringClass();
            } else {
                method = null;
            }

            classHierarchy = getIteratorOverClassHierarchy(clazz);
            locateNext();
        }

        @Override
        protected void locateNext() {
            while (!currentElementAnnotations.get().hasNext() && classHierarchy.hasNext()) {
                Class clazz = classHierarchy.next();

                try {
                    AnnotatedElement annotatedElement = method == null ? clazz : clazz.getDeclaredMethod(method.getName(), method.getParameterTypes());
                    currentElementAnnotations.set(Arrays.asList(annotatedElement.getAnnotations()).iterator());
                } catch (NoSuchMethodException e) {
                    // it's ok, method not declared in class clazz
                }
            }

            nextReference.set(currentElementAnnotations.get().hasNext() ? currentElementAnnotations.get().next() : null);
        }
    }

    private static class AnnotationFilterIterator<A extends Annotation> extends BaseIterator<A> {
        private final Iterator<Annotation> annotationIterator;
        private final Class<A> annotationClass;

        AnnotationFilterIterator(Iterator<Annotation> annotationIterator, Class<A> annotationClass) {
            this.annotationIterator = annotationIterator;
            this.annotationClass = annotationClass;

            locateNext();
        }

        @Override
        protected void locateNext() {
            while (annotationIterator.hasNext()) {
                Annotation annotation = annotationIterator.next();
                if (annotation.annotationType().equals(annotationClass)) {
                    nextReference.set((A) annotation);
                    return;
                }
            }

            nextReference.set(null);
        }
    }
}


//    private static class DeclaredInterfacesIterator extends AbstractIterator<Class> {
//        Iterator<Class> inner;
//
//        DeclaredInterfacesIterator(Class clazz) {
//            inner = Arrays.asList(clazz.getInterfaces()).iterator();
//        }
//
//        @Override
//        public boolean hasNext() {
//            return inner.hasNext();
//        }
//
//        @Override
//        public Class next() {
//            return inner.next();
//        }
//    }
//
//    public static Method locateMostCompatibleMethod(Method method, Collection<Method> methods) {
//        Method result = null;
//
//        for (Method probe : methods) {
//            if (!method.getReturnType().isAssignableFrom(probe.getReturnType())) {
//                continue;
//            }
//
//            if (!method.getName().equals(probe.getName())) {
//                continue;
//            }
//
//            if (method.getParameterTypes().length != probe.getParameterTypes().length) {
//                continue;
//            }
//
//
//            for (int index = 0; index < method.getParameterTypes().length; index++) {
//
//            }
//        }
//
//        return null;
//        return method1.getName().equals(method2.getName())
//                && method1.getReturnType().equals(method2.getReturnType())
//                && equalParamTypes(method1.getParameterTypes(), method2.getParameterTypes());
//    }
