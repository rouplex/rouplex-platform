package org.rouplex.platform.jaxrs.filter;

import org.rouplex.platform.jaxrs.security.RouplexSecurityContext;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.security.cert.X509Certificate;

/**
 * Filter which will locate the security context in {@link ContainerRequestContext} and will build a
 * {@link RouplexSecurityContext} from it.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class RouplexSecurityContextFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        Object value = requestContext.getProperty("javax.servlet.request.X509Certificate");
        X509Certificate certificate = null;

        if (value instanceof X509Certificate[]) {
            X509Certificate[] certificates = (X509Certificate[]) value;
            if (certificates.length > 0) {
                certificate = certificates[0];
            }
        }

        SecurityContext securityContext = requestContext.getSecurityContext();
        boolean isSecure = securityContext != null && securityContext.isSecure();

        requestContext.setSecurityContext(new RouplexSecurityContext(certificate, isSecure));
    }
}