package org.rouplex.commons.annotations;

import java.lang.annotation.*;

/**
 * Copied from apache commons, for now, might remove soon
 */
@Documented
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.CLASS)
public @interface ThreadSafe {
}
