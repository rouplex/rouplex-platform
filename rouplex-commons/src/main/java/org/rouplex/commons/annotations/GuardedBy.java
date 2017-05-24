package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * A not-checked annotation for documenting purposes only. Its value is the string representation of the field which
 * will be used as a lock to protect the field or method annotated by this annotation.
 *
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Target({ ElementType.FIELD, ElementType.METHOD })
@Retention(RetentionPolicy.CLASS)
public @interface GuardedBy {
    String value();
}