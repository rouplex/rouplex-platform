package org.rouplex.commons.builders;

import org.rouplex.commons.annotations.NotThreadSafe;
import org.rouplex.commons.annotations.Nullable;

/**
 * A base builder to be used for cases where multiple instances of a certain class can be built off a builder.
 *
 * Not thread safe.
 *
 * @param <T> The type of the instance to be built off this builder.
 * @param <B> The type of the Builder itself
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@NotThreadSafe
public abstract class MultiInstanceBuilder<T, B extends MultiInstanceBuilder> {
    // reference to "this" to get around generics invariance.
    protected B builder;
    protected Object attachment;

    protected MultiInstanceBuilder() {
        builder = (B) this;
    }

    public B withAttachment(@Nullable Object attachment) {
        this.attachment = attachment;
        return builder;
    }

    public Object getAttachment() {
        return attachment;
    }

    /**
     * Override this method to perform checks for needed values for the instance of type T to be built
     */
    protected abstract void checkCanBuild();
    public abstract T build() throws Exception;
}
