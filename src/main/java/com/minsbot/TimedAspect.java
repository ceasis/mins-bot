package com.minsbot;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AOP aspect that measures and logs execution time for methods annotated with {@link Timed}
 * (or all public methods in a class annotated with {@code @Timed}).
 *
 * <p>Output example:
 * <pre>[Timed] ChatService.getReply(message="hello") — 1234ms</pre>
 */
@Aspect
@Component
public class TimedAspect {

    private static final Logger log = LoggerFactory.getLogger(TimedAspect.class);

    /** Matches methods directly annotated with @Timed. */
    @Around("@annotation(com.minsbot.Timed)")
    public Object timeMethod(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp);
    }

    /** Matches all public methods in classes annotated with @Timed. */
    @Around("@within(com.minsbot.Timed) && execution(public * *(..))")
    public Object timeClassMethods(ProceedingJoinPoint pjp) throws Throwable {
        return measure(pjp);
    }

    private Object measure(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String className = sig.getDeclaringType().getSimpleName();
        String methodName = sig.getName();
        String params = formatParams(sig.getParameterNames(), pjp.getArgs());

        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Timed] {}.{}({}) — {}ms", className, methodName, params, elapsed);
            return result;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Timed] {}.{}({}) — {}ms EXCEPTION: {}", className, methodName, params, elapsed, t.getMessage());
            throw t;
        }
    }

    private static String formatParams(String[] names, Object[] args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            if (names != null && i < names.length) {
                sb.append(names[i]).append('=');
            }
            sb.append(truncate(args[i]));
        }
        return sb.toString();
    }

    private static String truncate(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof byte[]) return "byte[" + ((byte[]) obj).length + "]";
        if (obj.getClass().isArray()) return Arrays.toString((Object[]) obj);
        String s = obj.toString();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
