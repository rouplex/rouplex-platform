package org.rouplex.jaxrs.client;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Path("/security1")
public interface JaxrsTestApi2 extends JaxrsTestApi {
    @GET
    @Path("/ping")
    PingResponse ping();
//
//    @POST
//    @Path("/ping")
//    PingResponse ping(String payload);
}