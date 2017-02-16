package org.rouplex.service.security;

import org.glassfish.hk2.api.Factory;
import org.rouplex.platform.jaxrs.security.RouplexSecurityContext;

import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.SecurityContext;

/**
 * Factory class to make the RouplexSecurityContext injectable using {@link @Inject} or {@link @Context}
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexSecurityContextFactory implements Factory<RouplexSecurityContext> {
    private final ContainerRequestContext requestContext;

    @Inject
    public RouplexSecurityContextFactory(ContainerRequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    public RouplexSecurityContext provide() {
        SecurityContext securityContext = requestContext.getSecurityContext();
        return securityContext instanceof RouplexSecurityContext ? (RouplexSecurityContext) securityContext : null;
    }

    @Override
    public void dispose(RouplexSecurityContext rouplexSecurityContext) {
    }
}
