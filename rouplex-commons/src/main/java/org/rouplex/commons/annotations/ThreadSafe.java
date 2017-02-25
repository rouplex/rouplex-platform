package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * Copied from apache commons, for now, might remove soon
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface ThreadSafe {
}
