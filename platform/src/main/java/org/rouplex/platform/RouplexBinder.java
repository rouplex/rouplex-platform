package org.rouplex.platform;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface RouplexBinder<T extends RouplexService> {
    void bindServiceProvider(T serviceProvider);
}
