package org.rouplex.jaxrs.client;

import org.junit.Test;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public class RouplexClientBuilderTest {

    @Test
    public void test1() {
        RouplexClientBuilder rouplexClientBuilder = new RouplexClientBuilder(null);
        JaxrsTestApi jaxrsTestApi = RouplexClientBuilder.build2(JaxrsTestApi2.class);
        jaxrsTestApi.ping();
    }
}