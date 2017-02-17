package org.rouplex.jaxrs.client;

import org.junit.Test;

import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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


    //    Connector connector = mock(Connector.class);

    interface Plot {

    }

    interface Kot {
        Future<String> getKot(int kk) throws ExecutionException;
    }

    @Test
    public void test() throws Exception {
//        when(connector.getConfiguration()).thenReturn(mock(Configuration.class));
//        when(connector.putRequest(anyString(), any(byte[].class), any(Processor.class))).thenReturn(mock(Configuration.class));

//        Kot kot = new JsonClientBuilder<Kot>(Kot.class, new Connector(null)).withStack(null).withRequestTimeoutMillis(1000).build();
        Socket s = new Socket();

//        Future<String> reply = kot.getKot(11);
//        String pp = reply.get();
    }
}