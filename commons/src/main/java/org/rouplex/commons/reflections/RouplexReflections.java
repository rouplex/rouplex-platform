package org.rouplex.commons.reflections;

import org.rouplex.commons.Optional;
import org.rouplex.commons.Predicate;
import org.rouplex.commons.collections.AbstractIterator;
import org.rouplex.commons.collections.RouplexCollections;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexReflections {
    Class<?> clazz;

    public RouplexReflections(Class<?> clazz) {
        this.clazz = clazz;
    }

    protected Iterator<Class<?>> getDeclaredTypesIterator(Predicate<Class<?>> predicate) {
        return new ClassAndInterfaceIterator(RouplexCollections.<Class<?>> singletonIterator(clazz), predicate);
    }

    public Collection<Class<?>> getDeclaredTypes(Predicate<Class<?>> predicate) {
        return RouplexCollections.getCollection(getDeclaredTypesIterator(predicate));
    }

    public Collection<Class<?>> getDeclaredTypes() {
        return getDeclaredTypes(null);
    }

    protected Iterator<Class<?>> getSupperClassesIterator(Predicate<Class<?>> predicate) {
        return new SuperClassesIterator(clazz, predicate);
    }

    public Collection<Class<?>> getSupperClasses(Predicate<Class<?>> predicate) {
        return RouplexCollections.getCollection(getSupperClassesIterator(predicate));
    }

    public Collection<Class<?>> getSupperClasses() {
        return getSupperClasses(null);
    }

    protected Iterator<Class<?>> getSupperTypesIterator(Predicate<Class<?>> predicate) {
        return new ClassAndInterfaceIterator(getSupperClassesIterator(null), predicate);
    }

    public Collection<Class<?>> getSupperTypes(Predicate<Class<?>> predicate) {
        return RouplexCollections.getCollection(getSupperTypesIterator(predicate));
    }

    public Collection<Class<?>> getSupperTypes() {
        return getSupperTypes(null);
    }

    protected Iterator<Annotation> getAnnotationsOfSuperTypesIterator(Predicate<Annotation> predicate) {
        return new AnnotationsOfSuperTypesIterator(clazz, predicate);
    }

    public Collection<Annotation> getAnnotationsOfSuperTypes(Predicate<Annotation> predicate) {
        return RouplexCollections.getCollection(getAnnotationsOfSuperTypesIterator(predicate));
    }

    public Collection<Annotation> getAnnotationsOfSuperTypes() {
        return getAnnotationsOfSuperTypes((Predicate<Annotation>) null);
    }

    public <A extends Annotation> Collection<A> getAnnotationsOfSuperTypes(final Class<A> annotationType) {
        Iterator<Annotation> iterator = getAnnotationsOfSuperTypesIterator(new Predicate<Annotation>() {
            @Override
            public boolean test(Annotation value) {
                return value.annotationType().equals(annotationType);
            }
        });

        return (Collection<A>) RouplexCollections.getCollection(iterator);
    }

    public <A extends Annotation> Optional<A> getUniqueAnnotationInSuperTypes(Class<A> annotationClass) {
        Iterator<A> annotationsIterator = getAnnotationsOfSuperTypes(annotationClass).iterator();
        return annotationsIterator.hasNext() ? Optional.of(annotationsIterator.next()) : Optional.<A> empty();
    }

    public Method getDeclaredMethod(Method method) {
        try {
            return clazz.getDeclaredMethod(method.getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            // no problem, return null
            return null;
        }
    }

    public Collection<Method> getPublicAbstractInstanceMethods() {
        Collection<Method> methods = new ArrayList<Method>();

        for (Class klass : getSupperTypes()) {
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

    private static class SuperClassesIterator extends AbstractIterator<Class<?>> {
        private final Class<?> clazz;

        SuperClassesIterator(Class<?> clazz, Predicate<Class<?>> predicate) {
            super(predicate);
            this.clazz = clazz;
            locateNextFiltered();
        }

        @Override
        protected void locateNext() {
            next = next == null ? clazz : next.getSuperclass();
        }
    }

    private static class ClassAndInterfaceIterator extends AbstractIterator<Class<?>> {
        private final Queue<Class<?>> remainingClasses;
        private final Set<Class<?>> visitedClasses = new HashSet<Class<?>>();

        ClassAndInterfaceIterator(Iterator<Class<?>> classes, Predicate<Class<?>> predicate) {
            super(predicate);
            remainingClasses = new LinkedList<Class<?>>(RouplexCollections.getCollection(classes));

            locateNextFiltered();
        }

        @Override
        protected void locateNext() {
            while (!remainingClasses.isEmpty()) {
                next = remainingClasses.remove();

                if (visitedClasses.add(next)) {
                    remainingClasses.addAll(Arrays.asList(next.getInterfaces()));
                    return;
                }
            }

            next = null;
        }
    }

    private static class AnnotationsOfSuperTypesIterator extends AbstractIterator<Annotation> {
        private final Method method;
        private final Iterator<Class<?>> classHierarchy;
        private Iterator<Annotation> currentElementAnnotations = RouplexCollections.getEmptyIterator();

        AnnotationsOfSuperTypesIterator(AnnotatedElement annotatedElement, Predicate<Annotation> predicate) {
            super(predicate);
            Class<?> clazz = null;

            if (annotatedElement instanceof Class<?>) {
                clazz = (Class<?>) annotatedElement;
            }

            if (annotatedElement instanceof Method) {
                method = (Method) annotatedElement;
                clazz = method.getDeclaringClass();
            } else {
                method = null;
            }

            classHierarchy = new RouplexReflections(clazz).getSupperTypesIterator(null);
            locateNextFiltered();
        }

        @Override
        protected void locateNext() {
            while (!currentElementAnnotations.hasNext() && classHierarchy.hasNext()) {
                Class<?> clazz = classHierarchy.next();

                try {
                    AnnotatedElement annotatedElement = method == null ? clazz : clazz.getDeclaredMethod(method.getName(), method.getParameterTypes());
                    currentElementAnnotations = Arrays.asList(annotatedElement.getAnnotations()).iterator();
                } catch (NoSuchMethodException e) {
                    // it's ok, method not declared in class clazz
                }
            }

            next = currentElementAnnotations.hasNext() ? currentElementAnnotations.next() : null;
        }
    }
}
