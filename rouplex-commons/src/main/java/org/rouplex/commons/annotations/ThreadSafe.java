package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * A not-checked annotation for documenting purposes only.  Copied from apache commons, for now, might remove later.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface ThreadSafe {
}
