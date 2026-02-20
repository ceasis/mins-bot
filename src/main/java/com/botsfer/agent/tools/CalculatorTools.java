package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Safe arithmetic — no script injection. Only digits, +, -, *, /, ., (, ), and spaces.
 */
@Component
public class CalculatorTools {

    private final ToolExecutionNotifier notifier;

    public CalculatorTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Evaluate a numeric expression safely (e.g. '15 * 0.15' for 15% of 15, '100/3', '2^10'). " +
            "Supports +, -, *, /, parentheses, and ^ for power. Use for exact math instead of guessing.")
    public String calculate(
            @ToolParam(description = "Expression to evaluate, e.g. '280 * 0.15' or '(1+2)*3'") String expression) {
        if (expression == null || expression.isBlank()) {
            return "Please provide an expression (e.g. 280 * 0.15).";
        }
        notifier.notify("Calculating: " + expression);
        String cleaned = expression.trim().replace(" ", "").replace("×", "*").replace("÷", "/");
        if (!cleaned.matches("[0-9+\\-*/.()^\\s]+")) {
            return "Invalid characters. Only numbers and + - * / ( ) ^ are allowed.";
        }
        try {
            double result = eval(cleaned);
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                return "Result is not a finite number.";
            }
            // Avoid scientific notation for "normal" numbers
            String out = result == (long) result ? String.valueOf((long) result) : String.format("%.10g", result);
            return expression + " = " + out;
        } catch (Exception e) {
            return "Calculation error: " + e.getMessage();
        }
    }

    /** Minimal recursive-descent: term ( + term | - term )* ; term = factor ( * factor | / factor )* ; factor = number | ( expr ) | base ^ factor. */
    private static double eval(String s) {
        return new Object() {
            int i = 0;

            double parseExpr() {
                double v = parseTerm();
                while (i < s.length()) {
                    if (s.charAt(i) == '+') { i++; v += parseTerm(); }
                    else if (s.charAt(i) == '-') { i++; v -= parseTerm(); }
                    else break;
                }
                return v;
            }

            double parseTerm() {
                double v = parseFactor();
                while (i < s.length()) {
                    if (s.charAt(i) == '*') { i++; v *= parseFactor(); }
                    else if (s.charAt(i) == '/') { i++; v /= parseFactor(); }
                    else break;
                }
                return v;
            }

            double parseFactor() {
                if (i < s.length() && s.charAt(i) == '(') {
                    i++;
                    double v = parseExpr();
                    if (i < s.length() && s.charAt(i) == ')') i++;
                    return v;
                }
                double base = parseNumber();
                if (i < s.length() && s.charAt(i) == '^') {
                    i++;
                    return Math.pow(base, parseFactor());
                }
                return base;
            }

            double parseNumber() {
                int start = i;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                String n = s.substring(start, i);
                if (n.isEmpty()) throw new IllegalArgumentException("Expected number at position " + i);
                return Double.parseDouble(n);
            }
        }.parseExpr();
    }
}
