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
import org.rouplex.commons.reflections.RouplexReflections;
import org.rouplex.platform.RouplexBinder;
import org.rouplex.platform.RouplexService;
import org.rouplex.platform.jaxrs.filter.RouplexSecurityContextFilter;
import org.rouplex.platform.jaxrs.security.RouplexSecurityContext;
import org.rouplex.platform.jersey.security.RouplexSecurityContextFactory;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

//import org.rouplex.platform.RouplexBinder;

/**
 * This is the base class to be extended by the Rouplex applications
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexApplication extends ResourceConfig implements RouplexBinder {
    private final ServletContext servletContext;
    private final Set<Class<?>> swaggerEnabledResources = new HashSet();

    public RouplexApplication(@Context ServletContext servletContext) {
        this.servletContext = servletContext;
        //RouplexJerseyPlatform.getRouplexJerseyPlatform().registerConnector(); // disco service, metrics, logs etc
    }

    @PostConstruct
    void init() {
        initSecurity();
        initJacksonJaxbJsonProvider();
        initSwagger();
    }

    private String getApplicationPath() {
        Optional<ApplicationPath> applicationPath = new RouplexReflections(getClass()).getUniqueAnnotationInSuperTypes(ApplicationPath.class);
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
        if (!swaggerEnabledResources.isEmpty()) {
            register(ApiListingResource.class);
            register(SwaggerSerializers.class);

            BeanConfig beanConfig = new BeanConfig() {
                public Set<Class<?>> classes() {
                    return swaggerEnabledResources;
                }
            };

            beanConfig.setVersion("1.0.2");
            beanConfig.setTitle("Rouplex Services");
            beanConfig.setBasePath(servletContext.getContextPath() + getApplicationPath());

            beanConfig.setScan(true);
        }
    }

    public void bindResource(Class<?> clazz, boolean enableSwagger) {
        register(clazz);

        if (enableSwagger) {
            Collection<Class<?>> jerseyResources = new RouplexReflections(clazz).getSupperClasses(new Predicate<Class<?>>() {
                @Override
                public boolean test(Class<?> clazz) {
                    return !new RouplexReflections(clazz).getDeclaredTypes(new Predicate<Class<?>>() {
                        @Override
                        public boolean test(Class<?> value) {
                            return value.isAnnotationPresent(Path.class);
                        }
                    }).isEmpty();
                }
            });

            swaggerEnabledResources.addAll(jerseyResources);
        }

        // fish out coordinates of the resource being bound,
        // create a RouplexService instance,
        // and register it with the platform
        bindServiceProvider(new RouplexService(){});
    }

    @Override
    public void bindServiceProvider(RouplexService serviceProvider) {
        // register this with the platform
    }
}