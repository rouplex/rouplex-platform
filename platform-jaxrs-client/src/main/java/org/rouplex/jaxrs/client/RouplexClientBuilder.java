package org.rouplex.jaxrs.client;

import org.rouplex.common.reflections.RouplexReflections;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Configuration;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.KeyStore;
import java.util.*;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexClientBuilder<T> extends ClientBuilder {

    private static final Set<Annotation> jaxrsHttpMethods = new HashSet() {{
        add(DELETE.class);
        add(GET.class);
        add(HEAD.class);
        add(OPTIONS.class);
        add(POST.class);
        add(PUT.class);
    }};

    static class MethodStructure {
        String name;
        List args;
    }

    Class<T> clazz;

    RouplexClientBuilder(Class<T> clazz) {

    }

    public T build1(@Suspended Object obj) {
//        T t = (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, new InvocationHandler() {
//            @Override
//            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
//                String methodName = method.getName();
//                AsyncClientMethodInvoker invoker = invokers.get(methodName);
//                Object result;
//
//                try {
//                    if ((invoker != null)
//                            || (methodName.endsWith("$") && (invoker = invokers.get(methodName.substring(0, methodName.length() - 1))) != null)) {
//                        result = invoker.invoke(args);
//                    } else {
//                        result = method.invoke(proxy.getClass().getSuperclass(), args);
//                    }
//                    return result;
//                } catch (Throwable t) {
//                    throw t;
//                }
//            }
//        });
        return null;
    }

    public static <T> T build2(Class<T> clazz) {
        T t = (T) Proxy.newProxyInstance(RouplexClientBuilder.class.getClassLoader(), new Class[]{clazz}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                return null;
            }
        });

        return t;
    }

    static class MethodWrapper {
        private final Method method;

        MethodWrapper(Method method) {
            this.method = method;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MethodWrapper && RouplexReflections.equalSignatures(method, ((MethodWrapper) other).method);
        }

        @Override
        public int hashCode() {
            return method.hashCode() + method.getParameterTypes().length;
        }


    }



    public <T> T build(Class<T> clazz) {
        Collection<Class> classes = RouplexReflections.getClassHierarchyCollection(clazz);

        Path path = null;

        for (Annotation classAnnotation : RouplexReflections.getAnnotationsCollection(clazz)) {
            if (Path.class.isAssignableFrom(classAnnotation.getClass())) {
                Path probe = (Path) classAnnotation;
                if (path == null) {
                    path = probe;
                } else if (!path.value().equals(probe.value())) {
                    throw new IllegalArgumentException(String.format("Interface %s contains conflicting %s annotations %s != %s",
                            clazz.getSimpleName(), Path.class.getName(), path.value(), probe.value()));
                }
            }
        }

//        Set<Method> methods = new HashSet()
//        Collection<Method> methods = RouplexReflections.getPublicAbstractInstanceMethods(clazz);


        Collection<Annotation> classAnnotations = RouplexReflections.getAnnotationsCollection(clazz);
        return null;
    }

    public static RouplexClientBuilder newBuilder() {
        return new RouplexClientBuilder(null);
    }


    @Override
    public ClientBuilder withConfig(Configuration config) {
        return null;
    }

    @Override
    public ClientBuilder sslContext(SSLContext sslContext) {
        return null;
    }

    @Override
    public ClientBuilder keyStore(KeyStore keyStore, char[] password) {
        return null;
    }

    @Override
    public ClientBuilder trustStore(KeyStore trustStore) {
        return null;
    }

    @Override
    public ClientBuilder hostnameVerifier(HostnameVerifier verifier) {
        return null;
    }

    @Override
    public Client build() {
        return null;
    }

    @Override
    public Configuration getConfiguration() {
        return null;
    }

    @Override
    public ClientBuilder property(String name, Object value) {
        return null;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass) {
        return null;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, int priority) {
        return null;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Class<?>... contracts) {
        return null;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        return null;
    }

    @Override
    public ClientBuilder register(Object component) {
        return null;
    }

    @Override
    public ClientBuilder register(Object component, int priority) {
        return null;
    }

    @Override
    public ClientBuilder register(Object component, Class<?>... contracts) {
        return null;
    }

    @Override
    public ClientBuilder register(Object component, Map<Class<?>, Integer> contracts) {
        return null;
    }
}
