package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * A not-checked annotation for documenting purposes only.
 * 
 * @author Andi Mullaraj (andimullaraj at gmail.com)
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface NotNull {
}
