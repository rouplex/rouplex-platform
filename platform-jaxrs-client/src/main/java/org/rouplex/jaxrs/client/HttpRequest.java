package org.rouplex.jaxrs.client;

import java.util.List;
import java.util.SortedMap;

public class HttpRequest {
    private String authType;
    private SortedMap<String, List<String>> headers;
    private String method;
    private String pathInfo;
    private String pathTranslated;
    private String contextPath;
    private String queryString;
    private String remoteUser;
    private Principal userPrincipal;
    private String requestedSessionId;
    private String requestURI;
    private String requestURL;
    private String servletPath;
    private boolean requestedSessionIdValid;
    private boolean requestedSessionIdFromCookie;
    private boolean requestedSessionIdFromURL;

    private String payload;

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public SortedMap<String, List<String>> getHeaders() {
        return headers;
    }

    public void setHeaders(SortedMap<String, List<String>> headers) {
        this.headers = headers;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPathInfo() {
        return pathInfo;
    }

    public void setPathInfo(String pathInfo) {
        this.pathInfo = pathInfo;
    }

    public String getPathTranslated() {
        return pathTranslated;
    }

    public void setPathTranslated(String pathTranslated) {
        this.pathTranslated = pathTranslated;
    }

    public String getContextPath() {
        return contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public String getQueryString() {
        return queryString;
    }

    public void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    public String getRemoteUser() {
        return remoteUser;
    }

    public void setRemoteUser(String remoteUser) {
        this.remoteUser = remoteUser;
    }

    public Principal getUserPrincipal() {
        return userPrincipal;
    }

    public void setUserPrincipal(Principal userPrincipal) {
        this.userPrincipal = userPrincipal;
    }

    public String getRequestedSessionId() {
        return requestedSessionId;
    }

    public void setRequestedSessionId(String requestedSessionId) {
        this.requestedSessionId = requestedSessionId;
    }

    public String getRequestURI() {
        return requestURI;
    }

    public void setRequestURI(String requestURI) {
        this.requestURI = requestURI;
    }

    public String getRequestURL() {
        return requestURL;
    }

    public void setRequestURL(String requestURL) {
        this.requestURL = requestURL;
    }

    public String getServletPath() {
        return servletPath;
    }

    public void setServletPath(String servletPath) {
        this.servletPath = servletPath;
    }

    public boolean isRequestedSessionIdValid() {
        return requestedSessionIdValid;
    }

    public void setRequestedSessionIdValid(boolean requestedSessionIdValid) {
        this.requestedSessionIdValid = requestedSessionIdValid;
    }

    public boolean isRequestedSessionIdFromCookie() {
        return requestedSessionIdFromCookie;
    }

    public void setRequestedSessionIdFromCookie(boolean requestedSessionIdFromCookie) {
        this.requestedSessionIdFromCookie = requestedSessionIdFromCookie;
    }

    public boolean isRequestedSessionIdFromURL() {
        return requestedSessionIdFromURL;
    }

    public void setRequestedSessionIdFromURL(boolean requestedSessionIdFromURL) {
        this.requestedSessionIdFromURL = requestedSessionIdFromURL;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
