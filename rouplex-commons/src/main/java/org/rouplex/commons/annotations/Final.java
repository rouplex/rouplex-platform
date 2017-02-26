package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * In order to keep builders simple (without duplicating values that get sent to built instances anyway), we usually
 * create the instance within the builder then set the values directly to it whenever a setter is invoked with builder.
 * At the same time, the final fields of those instance cannot be marked as final since they get set via the builder
 * after the instance has been created (but not exposed to user). This is when this annotation can come handy for
 * marking tose fields with @Final
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Documented
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.CLASS)
public @interface Final {
}
