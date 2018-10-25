package org.rouplex.commons.builders;

import org.rouplex.commons.annotations.NotThreadSafe;
import org.rouplex.commons.annotations.Nullable;

/**
 * A base builder to be used for cases where only one instance can or must be built off a builder.
 *
 * Not thread safe.
 *
 * @param <T> The type of the instance to be built off this builder.
 * @param <B> The type of the Builder itself
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@NotThreadSafe
public abstract class SingleInstanceBuilder<T, B extends SingleInstanceBuilder> {
    // reference to "this" to get around generics invariance.
    // used also to check if the instance has already been built off this builder
    protected B builder;
    protected Object attachment;

    protected SingleInstanceBuilder() {
        builder = (B) this;
    }

    /**
     * Override this method to perform checks for needed values for the instance of type T to be built
     */
    protected abstract void checkCanBuild();
    public abstract T build() throws Exception;

    public B withAttachment(@Nullable Object attachment) {
        this.attachment = attachment;
        return builder;
    }

    public Object getAttachment() {
        return attachment;
    }

    /**
     * A basic check for preventing more than one isntance to be built off this builder
     */
    protected void checkNotBuilt() {
        if (builder == null) {
            throw new IllegalStateException(
                "This builder is already used. Create a new one to build a new instance.");
        }
    }

    protected void prepareBuild() throws Exception {
        checkNotBuilt();
        checkCanBuild();
        builder = null;
    }

    protected B cloneInto(B builder) {
        builder.attachment = attachment;
        return builder;
    }
}
