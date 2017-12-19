package org.rouplex.platform.io;

import java.util.UnknownFormatConversionException;
import java.util.concurrent.TimeUnit;

/**
 * A way to control the flow from a producer (or transmitter) of data.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
public abstract class Throttle {
    /**
     * Advise the producer to produce at a capped rate. The maxRate applies to the duration.
     *
     * As an example:
     *      maxRate=100 (bytes), duration=1, timeUnit=TimeUnit.MILLIS is different from
     *      maxRate=1000 (bytes), duration=10, timeUnit=TimeUnit.MILLIS since the former one is calculated every
     *      millisecond and the later is calculated every 10 milliseconds (allowing for occasional bursts up,
     *      as long as there are no more than 1000 bytes during the 10 milliseconds)
     *
     * @param maxRate
     *          the maximum rate, in the number of units known between the sender and receiver. That can be
     *          cumulative number of bytes, of objects, of objects of objects.
     * @param duration
     *          the duration for which the maxRate has to be capped
     * @param timeUnit
     *          the time unit related to the duration parameter
     * @throws UnsupportedOperationException
     *          if this function is not supported by the throttle
     */
    public void setMaxRate(long maxRate, long duration, TimeUnit timeUnit) throws UnsupportedOperationException {
        throw new UnknownFormatConversionException("setMaxRate() is not supported by this throttle");
    }

    /**
     * Advise the producer to pause sending data.
     *
     * @throws UnsupportedOperationException
     *          if this function is not supported by the throttle
     */
    public void pause() throws UnsupportedOperationException {
        throw new UnknownFormatConversionException("pause() is not supported by this throttle");
    }

    /**
     * Tell the producer that it can resume sending data.
     *
     * It prior to this call, a producer was asked to pause (and producer indicated it was going to honor it), then
     * this call will allow it to produce payloads again.
     */
    public abstract void resume();
}
