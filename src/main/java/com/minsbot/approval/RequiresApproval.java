package com.minsbot.approval;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool method as needing user approval before it runs. The {@link ApprovalAspect}
 * intercepts calls and blocks on {@link ToolPermissionService} until the user decides
 * (or Bypass-Permissions mode is on).
 *
 * <p>Example:
 * <pre>
 * &#64;RequiresApproval(value = RiskLevel.DESTRUCTIVE, summary = "Delete file {path}")
 * &#64;Tool(description = "...")
 * public String deleteFile(String path) { ... }
 * </pre>
 *
 * <p>The {@code summary} field is a short human-readable message shown in the approval
 * modal. Placeholders like {@code {path}} are substituted from the method's parameter
 * names when Spring AOP has parameter-name info available (compiled with {@code -parameters}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresApproval {

    /** Risk tier — controls default-grant behavior and modal styling. */
    RiskLevel value() default RiskLevel.DESTRUCTIVE;

    /**
     * Short, user-facing description of what will happen if approved. May contain
     * {@code {paramName}} placeholders. Example: "Delete the file {path}".
     */
    String summary() default "";
}
