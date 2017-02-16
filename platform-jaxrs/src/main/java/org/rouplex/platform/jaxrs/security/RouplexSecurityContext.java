package org.rouplex.platform.jaxrs.security;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.security.cert.X509Certificate;

/**
 * A generic {@link SecurityContext} implementation adding some more flavor to it.
 *
 * For the moment it can be instantiated with a {@link X509Certificate} which maps to a CLIENT_CERT auth_scheme but other
 * mechanisms will be extending it in the future (such as parsing apache headers if behind apache server)
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexSecurityContext implements SecurityContext {
    private final X509Certificate userX509Certificate;
    private final Principal userPrincipal;
    private final String authenticationScheme;
    private final boolean isSecure;

    public RouplexSecurityContext(X509Certificate userX509Certificate, boolean isSecure) {
        this.userX509Certificate = userX509Certificate;
        this.isSecure = isSecure;

        userPrincipal = userX509Certificate != null ? userX509Certificate.getSubjectDN() : null;
        authenticationScheme = SecurityContext.CLIENT_CERT_AUTH;
    }

    @Override
    public Principal getUserPrincipal() {
        return userPrincipal;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public boolean isSecure() {
        return isSecure;
    }

    @Override
    public String getAuthenticationScheme() {
        return authenticationScheme;
    }

    /**
     * Get the users certificate if present and verified result for the user/client behind the request.
     * For now that is equivalent to having a user principal.
     * Later it may change with addition of more authentication mechanisms.
     *
     * @return true if the user is authenticated.
     */
    public X509Certificate getUserX509Certificate() {
        return userX509Certificate;
    }

    /**
     * Get the authentication result for the user/client behind the request.
     * For now that is equivalent to having a user principal.
     * Later it may change with addition of more authentication mechanisms.
     *
     * @return true if the user is authenticated.
     */
    public boolean isAuthenticated() {
        return userPrincipal != null;
    }
}
