package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Target({ ElementType.FIELD, ElementType.METHOD })
@Retention(RetentionPolicy.CLASS)
public @interface GuardedBy {
    String value();
}