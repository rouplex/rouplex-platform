package org.rouplex.platform.rr;

/**
 * A multipurpose listener of generic types to be used where onEvent word is descriptive enough
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public interface EventListener<T> {
    /**
     * An event has occurred
     *
     * @param event
     *          The additional details about the event
     */
    void onEvent(T event);
}
