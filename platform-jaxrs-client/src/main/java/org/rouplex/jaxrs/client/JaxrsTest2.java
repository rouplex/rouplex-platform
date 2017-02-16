package org.rouplex.jaxrs.client;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Path("/security")
@Kot(hic=1)
public class JaxrsTest2 extends JaxrsTest {
    @GET
    @Path("/ping")
    public PingResponse ping() {
        return null;
    }

    @Path("/ping")
    public PingResponse ping(@Suspended AsyncResponse payload) {
        return null;
    }
}