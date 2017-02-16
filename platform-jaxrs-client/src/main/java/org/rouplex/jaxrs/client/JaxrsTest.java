package org.rouplex.jaxrs.client;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
//@Path("/security")
public class JaxrsTest implements JaxrsTestApi {
    @GET
    @Path("/ping")
    public PingResponse ping() {
        return null;
    }

    @Path("/ping")
    public PingResponse ping(String payload) {
        return null;
    }
}