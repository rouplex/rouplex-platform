package org.rouplex.platform.jersey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ResourceConfig;
import org.rouplex.commons.Optional;
import org.rouplex.commons.Predicate;
import org.rouplex.commons.reflections.RxReflections;
import org.rouplex.platform.RouplexBinder;
import org.rouplex.platform.RouplexPlatform;
import org.rouplex.platform.RouplexService;
import org.rouplex.platform.jaxrs.filter.RouplexSecurityContextFilter;
import org.rouplex.platform.jaxrs.security.RouplexSecurityContext;
import org.rouplex.platform.jersey.security.RouplexSecurityContextFactory;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * This is the base class to be extended by the Rouplex applications.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexJerseyApplication extends ResourceConfig implements RouplexBinder {
    protected class SwaggerBeanConfig extends BeanConfig {
        private final Set<Class<?>> swaggerEnabledResources = new HashSet<Class<?>>();

        SwaggerBeanConfig() {
            setVersion("1.0");
            setTitle("Rouplex Services");
        }

        @Override
        public Set<Class<?>> classes() {
            return swaggerEnabledResources;
        }

        void addClass(Class<?> clazz) {
            Collection<Class<?>> jerseyResources = new RxReflections(clazz).getSupperClasses(new Predicate<Class<?>>() {
                @Override
                public boolean test(Class<?> clazz) {
                    return !new RxReflections(clazz).getDeclaredTypes(new Predicate<Class<?>>() {
                        @Override
                        public boolean test(Class<?> value) {
                            return value.isAnnotationPresent(Path.class);
                        }
                    }).isEmpty();
                }
            });

            swaggerEnabledResources.addAll(jerseyResources);
        }

        public void scan() {
            if (!swaggerEnabledResources.isEmpty()) {
                register(ApiListingResource.class);
                register(SwaggerSerializers.class);

                setBasePath(servletContext.getContextPath() + getApplicationPath());
                setScan(true);
            }
        }
    }

    protected static class ExceptionEntity {
        String exceptionClass;
        String exceptionMessage;

        public ExceptionEntity(Exception exception) {
            this.exceptionClass = exception.getClass().toString();
            this.exceptionMessage = exception.getMessage();
        }

        public String getExceptionClass() {
            return exceptionClass;
        }

        public String getExceptionMessage() {
            return exceptionMessage;
        }
    }

    private final ServletContext servletContext;
    private final RouplexPlatform rouplexPlatform = new RouplexPlatform();
    private final SwaggerBeanConfig swaggerBeanConfig = new SwaggerBeanConfig();

    public RouplexJerseyApplication(@Context ServletContext servletContext) {
        this.servletContext = servletContext;
        //RouplexJerseyPlatform.getRouplexJerseyPlatform().registerConnector(); // disco service, metrics, logs etc
    }

    @PostConstruct
    protected void postConstruct() {
// todo finish         initConfiguration();
        initSecurity();
        initJacksonJaxbJsonProvider();
        initExceptionMapper();
        initSwagger();
    }

    private String getApplicationPath() {
        Optional<ApplicationPath> applicationPath = new RxReflections(getClass()).getUniqueAnnotationInSuperTypes(ApplicationPath.class);
        return applicationPath.isPresent() ? applicationPath.get().value() : "";
        // TODO, does servletContext contain the one from web.xml? If yes, great -- return that one instead of "";
    }

    protected void initJacksonJaxbJsonProvider() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        JacksonJaxbJsonProvider provider = new JacksonJaxbJsonProvider();
        provider.setMapper(mapper);
        register(provider);
    }

// todo finish protected void initConfiguration() {
//        register(new AbstractBinder() {
//            @Override
//            protected void configure() {
//                bindFactory(RouplexSecurityContextFactory.class).to(RouplexSecurityContext.class).in(RequestScoped.class);
//            }
//        });
//    }

    protected void initSecurity() {
        register(new RouplexSecurityContextFilter());
        register(new AbstractBinder() {
            @Override
            protected void configure() {
                bindFactory(RouplexSecurityContextFactory.class).to(RouplexSecurityContext.class).in(RequestScoped.class);
            }
        });
    }

    protected void initSwagger() {
        swaggerBeanConfig.scan();
    }

    protected void initExceptionMapper() {
        register(new ExceptionMapper<Exception>() {
            @Override
            public Response toResponse(Exception e) {
                return Response.status(500).entity(new ExceptionEntity(e)).build();
            }
        });
    }

    public BeanConfig getSwaggerBeanConfig() {
        return swaggerBeanConfig;
    }

    public void bindRouplexResource(final Class<?> clazz, boolean enableSwagger) {
        register(clazz);

        if (enableSwagger) {
            swaggerBeanConfig.addClass(clazz);
        }

        // fish out coordinates of the resource being bound, create a RouplexService instance, and register it with the
        // platform
        bindServiceProvider(new RouplexService() {
// todo finish
//            @Override
//            public String getServiceId() {
//                return clazz.getName();
//            }
        });
    }

// todo: Not a big deal but curious why does not the instance get registered as the class does.
//  public void bindResource(Object object, boolean enableSwagger) {
//        register(object);
//        ...
//  }

// todo finish
//    @Override
//    public RouplexPlatform getPlatform() {
//        return rouplexPlatform;
//    }

    @Override
    public void bindServiceProvider(RouplexService serviceProvider) {
        // register this with the platform
    }
}

