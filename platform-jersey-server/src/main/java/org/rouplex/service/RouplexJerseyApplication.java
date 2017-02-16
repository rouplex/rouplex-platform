package org.rouplex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.glassfish.jersey.server.ResourceConfig;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import org.rouplex.platform.jaxrs.filter.RouplexSecurityContextFilter;
import org.rouplex.platform.jaxrs.security.RouplexSecurityContext;
import org.rouplex.service.security.RouplexSecurityContextFactory;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import java.util.HashSet;
import java.util.Set;

/**
 * This is the base class to be extended by the Rouplex applications
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexJerseyApplication extends ResourceConfig {
    public static final String APP_PATH = "/rouplex";

    private final ServletContext servletContext;
    private final Set<Class<?>> swaggerEnabledResources = new HashSet();

    public RouplexJerseyApplication(@Context ServletContext servletContext) {
        this.servletContext = servletContext;

        initJacksonJaxbJsonProvider();
        initRouplexSecurity();
    }

    private void initJacksonJaxbJsonProvider() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        JacksonJaxbJsonProvider provider = new JacksonJaxbJsonProvider();
        provider.setMapper(mapper);
        register(provider);
    }

    private void initRouplexSecurity() {
        register(new RouplexSecurityContextFilter());
        register(new AbstractBinder() {
            @Override
            protected void configure() {
                bindFactory(RouplexSecurityContextFactory.class).to(RouplexSecurityContext.class).in(RequestScoped.class);
            }
        });
    }

    @PostConstruct
    private void initSwagger() {
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
            beanConfig.setBasePath(servletContext.getContextPath() + RouplexJerseyApplication.APP_PATH);

            beanConfig.setScan(true);
        }
    }

    protected void registerRouplexResource(Class clazz, boolean enableSwagger) {
        register(clazz);

        if (enableSwagger) {
            swaggerEnabledResources.addAll(new Reflections(ReflectionUtils.getAllSuperTypes(clazz)).getTypesAnnotatedWith(Path.class));
        }
    }
}
