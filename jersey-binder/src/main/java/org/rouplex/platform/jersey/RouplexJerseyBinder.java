package org.rouplex.platform.jersey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;
import org.rouplex.commons.configuration.Configuration;
import org.rouplex.platform.jaxrs.filter.RouplexSecurityContextFilter;
import org.rouplex.platform.jaxrs.security.RouplexSecurityContext;
import org.rouplex.platform.jersey.security.RouplexSecurityContextFactory;

import javax.servlet.ServletContext;
import javax.ws.rs.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */

public class RouplexJerseyBinder extends RouplexBinder {
    private final ServletContext servletContext;
    private final RouplexApplication rouplexApplication;

    private final Set<Class<?>> swaggerEnabledResources = new HashSet();
    private final Configuration configuration;

    public RouplexJerseyBinder(RouplexApplication rouplexApplication, ServletContext servletContext, Configuration configuration) {
        this.rouplexApplication = rouplexApplication;
        this.servletContext = servletContext;
        this.configuration = configuration;
    }

    protected void initJacksonJaxbJsonProvider() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        JacksonJaxbJsonProvider provider = new JacksonJaxbJsonProvider();
        provider.setMapper(mapper);
        rouplexApplication.register(provider);
    }

    protected void initSecurity() {
        rouplexApplication.register(new RouplexSecurityContextFilter());
        rouplexApplication.register(new AbstractBinder() {
            @Override
            protected void configure() {
                bindFactory(RouplexSecurityContextFactory.class).to(RouplexSecurityContext.class).in(RequestScoped.class);
            }
        });
    }

    protected void initSwagger() {
        if (!swaggerEnabledResources.isEmpty()) {
            rouplexApplication.register(ApiListingResource.class);
            rouplexApplication.register(SwaggerSerializers.class);

            BeanConfig beanConfig = new BeanConfig() {
                public Set<Class<?>> classes() {
                    return swaggerEnabledResources;
                }
            };

            beanConfig.setVersion("1.0.2");
            beanConfig.setTitle("Rouplex Services");
            beanConfig.setBasePath(servletContext.getContextPath() + rouplexApplication.getApplicationPath());

            beanConfig.setScan(true);
        }
    }

    public void bindResource(Class clazz, boolean enableSwagger) {
        rouplexApplication.register(clazz);

        if (enableSwagger) {
            swaggerEnabledResources.addAll(new Reflections(ReflectionUtils.getAllSuperTypes(clazz)).getTypesAnnotatedWith(Path.class));
        }
    }

    void init() {
        initSecurity();
        initJacksonJaxbJsonProvider();
        initSwagger();
    }
}
