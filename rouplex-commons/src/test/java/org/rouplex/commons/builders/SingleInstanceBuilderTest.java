package org.rouplex.commons.builders;

import org.junit.Assert;
import org.junit.Test;

public class SingleInstanceBuilderTest {

    @Test
    public void test() {
        SingleInstanceBuilder builder = new SingleInstanceBuilder() {
            @Override
            protected void checkCanBuild() {
            }

            @Override
            public Object build() throws Exception {
                return null;
            }
        };

        Object attachment = new Object();
        builder.withAttachment(attachment);

        SingleInstanceBuilder clone = builder.cloneInto(new SingleInstanceBuilder() {
            @Override
            protected void checkCanBuild() {
            }

            @Override
            public Object build() throws Exception {
                return null;
            }
        });

        Assert.assertEquals(attachment, clone.getAttachment());
    }
}