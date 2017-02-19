package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface Nullable {
}
