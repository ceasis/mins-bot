package com.minsbot.agent.tools;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that intercepts every @Tool method call and logs:
 * tool name, parameters, execution time, and result summary.
 * Also caps tool output to MAX_OUTPUT_CHARS to prevent Jackson/Spring AI overflow.
 */
@Aspect
@Component
public class ToolCallLogger {

    private static final int MAX_OUTPUT_CHARS = 50_000;

    /** Tools that fire too frequently — suppress their logs to reduce noise. */
    private static final java.util.Set<String> QUIET_TOOLS = java.util.Set.of(
            "ClipboardTools.getClipboardText",
            "ClipboardTools.setClipboardText",
            "TaskStatusTool.taskStatus"
    );

    @Around("@annotation(tool)")
    public Object logToolCall(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String methodName = sig.getDeclaringType().getSimpleName() + "." + sig.getName();

        boolean quiet = QUIET_TOOLS.contains(methodName);
        String[] paramNames = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();

        // Build param string
        StringBuilder params = new StringBuilder();
        if (paramNames != null && args != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (i > 0) params.append(", ");
                params.append(paramNames[i]).append("=").append(truncate(args[i], 120));
            }
        }

        if (!quiet) {
            System.out.println("[TOOL-CALL] >>> " + methodName + "(" + params + ")");
            System.out.println("[TOOL-CALL]     description: " + tool.description());
        }

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;

            // Cap tool output to prevent Jackson StreamConstraintsException
            if (result instanceof String s && s.length() > MAX_OUTPUT_CHARS) {
                result = s.substring(0, MAX_OUTPUT_CHARS)
                        + "\n...(output truncated at " + MAX_OUTPUT_CHARS + " chars, total was " + s.length() + ")";
                if (!quiet) System.out.println("[TOOL-CALL] <<< " + methodName + " returned in " + elapsed + "ms => TRUNCATED from " + s.length() + " to " + MAX_OUTPUT_CHARS + " chars");
            } else {
                if (!quiet) System.out.println("[TOOL-CALL] <<< " + methodName + " returned in " + elapsed + "ms => " + truncate(result, 200));
            }
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            if (!quiet) System.out.println("[TOOL-CALL] !!! " + methodName + " FAILED in " + elapsed + "ms => " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    private static String truncate(Object obj, int maxLen) {
        if (obj == null) return "null";
        String s = obj.toString();
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(" + s.length() + " chars)";
    }
}
