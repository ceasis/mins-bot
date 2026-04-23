package com.minsbot.approval;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring AOP advice that enforces {@link RequiresApproval}. Every call to a tool
 * method carrying the annotation routes through {@link ToolPermissionService#require}
 * before the method body runs. On denial, the aspect returns a short string
 * (matching the usual String-returning @Tool convention) instead of throwing —
 * LLM callers handle this gracefully as a normal tool result.
 */
@Aspect
@Component
public class ApprovalAspect {

    private static final Logger log = LoggerFactory.getLogger(ApprovalAspect.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    private final ToolPermissionService permissions;

    public ApprovalAspect(ToolPermissionService permissions) {
        this.permissions = permissions;
    }

    @Around("@annotation(com.minsbot.approval.RequiresApproval)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        RequiresApproval ann = method.getAnnotation(RequiresApproval.class);
        if (ann == null) return pjp.proceed();

        String toolName = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        String summary = renderSummary(ann.summary(), sig.getParameterNames(), pjp.getArgs(), toolName);

        ToolPermissionService.Decision decision = permissions.require(toolName, ann.value(), summary);
        if (!decision.allowed()) {
            log.info("[Approval] DENIED {} — {}", toolName, decision.reason());
            // Return a friendly tool-result string rather than throwing; LLMs handle this better.
            if (method.getReturnType() == String.class) {
                return "Denied by user (approval gate): " + summary;
            }
            throw new SecurityException("User denied approval for " + toolName);
        }
        log.info("[Approval] ALLOWED {} — {}", toolName, decision.reason());
        return pjp.proceed();
    }

    /**
     * Substitute {paramName} placeholders with actual argument values. Falls back
     * to the tool name if the template is blank or parameter names are unavailable
     * (the default on a non-{@code -parameters}-compiled build).
     */
    private static String renderSummary(String template, String[] paramNames, Object[] args, String toolName) {
        if (template == null || template.isBlank()) {
            return "Run " + toolName;
        }
        if (paramNames == null || args == null) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = "?";
            for (int i = 0; i < paramNames.length; i++) {
                if (name.equals(paramNames[i]) && i < args.length) {
                    Object v = args[i];
                    value = (v == null) ? "(null)" : truncate(String.valueOf(v), 120);
                    break;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
