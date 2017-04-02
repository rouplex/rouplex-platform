package org.rouplex.platform.io;

import java.util.concurrent.TimeUnit;

/**
 * A way to control the flow from a producer (or transmitter) of data
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public abstract class Throttle {
    /**
     * Advise the throttle to produce at a capped rate. The maxRate applies to the duration.
     *
     * As an example,
     *      maxRate=100 (bytes), duration=1, timeUnit=TimeUnit.MILLIS is different from
     *      maxRate=1000 (bytes), duration=10, timeUnit=TimeUnit.MILLIS since the second one is calculated every 10
     *      milliseconds (allowing for occasional bursts up, if the rest of the timeslot is relatively quiet)
     *
     * @param maxRate
     *          The maximum rate, in the number of units known between the sender and receiver. That can be
     *          cumulative number of bytes, of objects, of objects of objects.
     * @param duration
     *          The duration for which the maxRate has to be capped
     * @param timeUnit
     *          The time unit related to the duration parameter
     * @return
     *          True if the throttle will be honoring this
     */
    public boolean setMaxRate(long maxRate, long duration, TimeUnit timeUnit) {
        return false;
    }

    /**
     * Advise the throttle to pause sending data
     *
     * @return
     *          True if the throttle will be honoring this. This default implementation returns false since it does
     *          nothing to pause anything.
     */
    public boolean pause() {
        return false;
    }

    /**
     * Tell the throttle that it can resume sending data.
     *
     * It may be that the producer never stopped sending data, most of which has been negatively acknowledged
     * ({@link SendChannel#send(Object)} returned false) anyway. But in the case the producer has honored a
     * {@link #pause()} (or a {@link SendChannel#send(Object)} which returned false), now it has a way to know when to
     * resume now.
     */
    public abstract void resume();
}
