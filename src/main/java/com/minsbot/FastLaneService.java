package com.minsbot;

import com.minsbot.agent.tools.ContinueProjectTools;
import com.minsbot.agent.tools.DevServerTools;
import com.minsbot.agent.tools.LogControlTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synchronous fast-lane for short, stateless user messages. Runs BEFORE the
 * message is queued to the main agent loop — so when a long tool call (e.g.
 * a 90-second Claude Code run) is blocking the loop, a chat message like
 * "hi", "what is 1+1?", or "kill port 8080" still gets answered immediately
 * without waiting in line.
 *
 * <p>Returns {@code null} when no pattern matched → ChatService continues to
 * the normal main-loop queue.</p>
 */
@Service
public class FastLaneService {

    private static final Logger log = LoggerFactory.getLogger(FastLaneService.class);

    private final DevServerTools devServerTools;
    private final LogControlTools logControlTools;
    private final ContinueProjectTools continueProjectTools;

    public FastLaneService(DevServerTools devServerTools,
                           LogControlTools logControlTools,
                           ContinueProjectTools continueProjectTools) {
        this.devServerTools = devServerTools;
        this.logControlTools = logControlTools;
        this.continueProjectTools = continueProjectTools;
    }

    /** Return a canned reply if the message matches a known fast-lane pattern, or null to queue normally. */
    public String tryHandle(String trimmed) {
        if (trimmed == null) return null;
        String s = trimmed.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase();

        // ── Greetings & small talk ──────────────────────────────────────
        if (lower.matches("^(hi|hello|hey|howdy|sup|yo|morning|good morning|good evening|good afternoon)[!.?]*$")) {
            return "Hi! What can I help with?";
        }
        if (lower.matches("^(thanks|thank you|ty|thx|cheers|appreciate it)[!.?]*$")) {
            return "You're welcome!";
        }
        if (lower.matches("^(bye|goodbye|see ya|later|cya)[!.?]*$")) {
            return "See you later!";
        }
        if (lower.matches("^(ok|okay|cool|great|nice|got it|sounds good|k)[!.?]*$")) {
            return "👍";
        }

        // ── Time / date ─────────────────────────────────────────────────
        if (lower.matches("^(what('?s| is) )?(the )?(current )?(time|date|date and time|datetime)[?!.]*$")) {
            return "It's " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d yyyy — h:mm a"));
        }

        // ── Basic arithmetic ────────────────────────────────────────────
        Matcher math = Pattern.compile(
                "^(?:what(?:'s| is)\\s+)?(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/x])\\s*(-?\\d+(?:\\.\\d+)?)\\s*[?!.]*$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (math.matches()) {
            try {
                double a = Double.parseDouble(math.group(1));
                double b = Double.parseDouble(math.group(3));
                String op = math.group(2);
                double result;
                switch (op) {
                    case "+": result = a + b; break;
                    case "-": result = a - b; break;
                    case "*": case "x": result = a * b; break;
                    case "/":
                        if (b == 0) return a + " / 0 is undefined.";
                        result = a / b; break;
                    default: return null;
                }
                String formatted = (result == Math.floor(result) && !Double.isInfinite(result))
                        ? Long.toString((long) result)
                        : Double.toString(result);
                return a + " " + op + " " + b + " = " + formatted;
            } catch (NumberFormatException ignored) { /* fall through */ }
        }

        // ── Port / dev-server commands ──────────────────────────────────
        // "kill port 8080", "kill app on port 8080", "free up port 3000", "stop port 5050"
        Matcher killPort = Pattern.compile(
                "^(?:please\\s+)?(?:kill|stop|free(?:\\s+up)?|terminate)\\s+(?:the\\s+)?(?:app\\s+(?:on\\s+)?)?port\\s+(\\d+)[?!.]*$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (killPort.matches()) {
            int port = Integer.parseInt(killPort.group(1));
            log.info("[FastLane] killProcessOnPort({})", port);
            return devServerTools.killProcessOnPort(port);
        }

        // "what's on port 8080", "who is using port 8080", "who's on port 3000"
        Matcher whoPort = Pattern.compile(
                "^(?:what(?:'s| is)|who(?:'s| is)|whats)\\s+(?:on|using|listening\\s+on)\\s+port\\s+(\\d+)[?!.]*$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (whoPort.matches()) {
            int port = Integer.parseInt(whoPort.group(1));
            log.info("[FastLane] whoIsUsingPort({})", port);
            return devServerTools.whoIsUsingPort(port);
        }

        // ── Log-level control ──────────────────────────────────────────
        // "mute recurring", "mute recurring logs", "quiet watcher logs"
        Matcher muteLog = Pattern.compile(
                "^(?:please\\s+)?(mute|silence|turn\\s+off)\\s+([\\w-]+)(?:\\s+logs?)?[?!.]*$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (muteLog.matches()) {
            String name = muteLog.group(2);
            log.info("[FastLane] muteLogger({})", name);
            return logControlTools.muteLogger(name);
        }
        Matcher quietLog = Pattern.compile(
                "^(?:please\\s+)?(quiet|hush|tone\\s+down)\\s+([\\w-]+)(?:\\s+logs?)?[?!.]*$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (quietLog.matches()) {
            String name = quietLog.group(2);
            log.info("[FastLane] quietLogger({})", name);
            return logControlTools.quietLogger(name);
        }

        // ── Project lookups ────────────────────────────────────────────
        if (lower.matches("^(list|show)\\s+(my\\s+)?(generated\\s+)?projects?[?!.]*$")) {
            log.info("[FastLane] listMyProjects()");
            return continueProjectTools.listMyProjects();
        }

        // ── Dev-server listing / logs ──────────────────────────────────
        if (lower.matches("^(list|show)\\s+(?:running\\s+|my\\s+)?dev\\s+servers?[?!.]*$")) {
            log.info("[FastLane] listDevServers()");
            return devServerTools.listDevServers();
        }

        // No match — let the main loop handle it.
        return null;
    }
}
