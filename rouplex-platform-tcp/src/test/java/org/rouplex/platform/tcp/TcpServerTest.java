package org.rouplex.platform.tcp;

import org.junit.Assert;
import org.junit.Test;

public class TcpServerTest {

    @Test
    public void test() {
        TcpServer.TcpServerBuilder builder = new TcpServer.TcpServerBuilder((TcpReactor.TcpSelector) null) {
            @Override
            protected void checkCanBuild() {
            }

            @Override
            public Object build() throws Exception {
                return null;
            }
        };

        builder.withBacklog(123);

        TcpServer.TcpServerBuilder clone = builder.cloneInto(new TcpServer.TcpServerBuilder((TcpReactor.TcpSelector) null) {
            @Override
            protected void checkCanBuild() {
            }

            @Override
            public Object build() throws Exception {
                return null;
            }
        });

        Assert.assertEquals(123, clone.backlog);
    }
}
