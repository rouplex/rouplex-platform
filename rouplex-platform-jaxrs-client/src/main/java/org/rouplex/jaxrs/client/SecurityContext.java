package org.rouplex.jaxrs.client;

import java.security.cert.X509Certificate;

/**
 * Work in progress -- Do not use!
 */
public class SecurityContext {
    private X509Certificate userX509Certificate;
    private Principal userPrincipal;
    private String authenticationScheme;
    private boolean isSecure;
    private boolean isAuthenticated;

    public X509Certificate getUserX509Certificate() {
        return userX509Certificate;
    }

    public void setUserX509Certificate(X509Certificate userX509Certificate) {
        this.userX509Certificate = userX509Certificate;
    }

    public Principal getUserPrincipal() {
        return userPrincipal;
    }

    public void setUserPrincipal(Principal userPrincipal) {
        this.userPrincipal = userPrincipal;
    }

    public String getAuthenticationScheme() {
        return authenticationScheme;
    }

    public void setAuthenticationScheme(String authenticationScheme) {
        this.authenticationScheme = authenticationScheme;
    }

    public boolean isSecure() {
        return isSecure;
    }

    public void setSecure(boolean secure) {
        isSecure = secure;
    }

    public boolean isAuthenticated() {
        return isAuthenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        isAuthenticated = authenticated;
    }
}