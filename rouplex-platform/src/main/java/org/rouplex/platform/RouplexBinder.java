package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RouplexBinder<T extends RouplexService> {
    /**
     * Return the {@link RouplexPlatform} to which this binder is bound to, if any.
     *
     * @return The platform bound to or noll if not bound.
     */
    // todo finish RouplexPlatform getPlatform();

    /**
     * Bind a new service provider to this binder.
     *
     * @param serviceProvider
     *      The service provider to be bound
     */
    void bindServiceProvider(T serviceProvider);
}
