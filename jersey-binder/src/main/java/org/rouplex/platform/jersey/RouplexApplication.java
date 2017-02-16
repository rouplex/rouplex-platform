package org.rouplex.platform.jersey;

import org.glassfish.jersey.server.ResourceConfig;
import org.rouplex.commons.Optional;
import org.rouplex.commons.reflections.RouplexReflections;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Context;

//import org.rouplex.platform.RouplexBinder;

/**
 * This is the base class to be extended by the Rouplex applications
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexApplication extends ResourceConfig {
    protected final RouplexJerseyBinder binder;

    public RouplexApplication(@Context ServletContext servletContext) {
        binder = new RouplexJerseyBinder(this, servletContext, null);
        //RouplexJerseyPlatform.getRouplexJerseyPlatform().registerConnector(); // disco service, metrics, logs etc
    }

    @PostConstruct
    private void initAndBind() {
        binder.init();
    }

    String getApplicationPath() {
        Optional<ApplicationPath> applicationPath = RouplexReflections.getFirstAnnotationOfType(getClass(), ApplicationPath.class);
        return applicationPath.isPresent() ? applicationPath.get().value() : "";
        // TODO, does servletContext contain the one from web.xml? If yes, great -- return that one instead of "";
    }
}