package com.minsbot.offline;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Spring AOP advice that enforces {@link RequiresOnline}. When the user has flipped
 * offline mode on, every annotated method short-circuits before hitting the network.
 *
 * <p>For {@code String}-returning methods (common in our {@code @Tool} surface) the
 * aspect returns {@link OfflineModeService#blockReasonOrNull}. For everything else
 * it throws {@link OfflineModeService.OfflineModeException} — callers that don't
 * already handle that deserve to be aware of the leak.</p>
 */
@Aspect
@Component
public class OnlineAspect {

    private static final Logger log = LoggerFactory.getLogger(OnlineAspect.class);

    private final OfflineModeService offline;

    public OnlineAspect(OfflineModeService offline) {
        this.offline = offline;
    }

    @Around("@annotation(com.minsbot.offline.RequiresOnline)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!offline.isOffline()) return pjp.proceed();

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        RequiresOnline ann = method.getAnnotation(RequiresOnline.class);
        String label = (ann == null || ann.value().isBlank())
                ? method.getDeclaringClass().getSimpleName() + "." + method.getName()
                : ann.value();

        log.info("[Offline] BLOCKED cloud call: {}", label);

        if (method.getReturnType() == String.class) {
            return offline.blockReasonOrNull(label);
        }
        throw new OfflineModeService.OfflineModeException(label);
    }
}
