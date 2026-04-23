package com.minsbot.offline;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that reaches the network. When {@link OfflineModeService#isOffline()}
 * is on, {@link OnlineAspect} short-circuits the call — returns a polite explanation
 * for {@code String} methods, throws {@link OfflineModeService.OfflineModeException}
 * otherwise. Uniform alternative to hand-injecting {@code OfflineModeService} in 20+
 * classes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresOnline {
    /** Human-readable label for the blocked operation. Shown in the offline-mode error string. */
    String value() default "";
}
