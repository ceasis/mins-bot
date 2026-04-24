package com.minsbot.skills.watcher;

import com.minsbot.agent.tools.ToolExecutionNotifier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI-callable Watcher tools. Lets the user say things like:
 *   "Watch nike.com.ph Jordan 11 Gamma size 9.5 every 15 min, email me when in stock"
 *   "List my watchers"
 *   "Stop watching the Jordan 11"
 */
@Component
public class WatcherTools {

    private final WatcherService service;
    private final WatcherConfig.WatcherProperties properties;
    private final ToolExecutionNotifier notifier;

    public WatcherTools(WatcherService service,
                        WatcherConfig.WatcherProperties properties,
                        ToolExecutionNotifier notifier) {
        this.service = service;
        this.properties = properties;
        this.notifier = notifier;
    }

    @Tool(description = "PREFERRED for product stock / size / restock / sneaker / shoe-size-availability watching. "
            + "Creates a recurring watcher that polls a product URL, detects specific size availability and price, "
            + "and sends the user an email (or Discord/ntfy webhook) ONLY when the item flips to in-stock "
            + "(and optionally below a price ceiling). Use THIS tool whenever the "
            + "user asks to 'watch', 'track', 'monitor', 'notify me', 'alert me', 'check every N minutes', "
            + "'email me when in stock', 'let me know when back in stock', or similar, for a PRODUCT or SHOE or "
            + "CLOTHING or SIZE on Nike, Shopee, Lazada, Zalora, or any e-commerce URL. "
            + "Adapters: 'nike-ph' (nike.com/ph product pages — target is the exact shoe size like '9.5'); "
            + "'generic-http' (any other site — target is a regex that must appear on the page for in-stock). "
            + "Always pass a numeric maxPrice (0 = ignore price, >0 = only fire when price is at or below that number). "
            + "Returns the created watcher id. The user can manage watchers in the Watcher tab.")
    public String createWatcher(
            @ToolParam(description = "Short human label, e.g. 'Jordan 11 Gamma size 9.5'") String label,
            @ToolParam(description = "Product page URL") String url,
            @ToolParam(description = "Adapter name: 'nike-ph' or 'generic-http'") String adapter,
            @ToolParam(description = "Adapter target — shoe size for nike-ph (e.g. '9.5'), regex for generic-http") String target,
            @ToolParam(description = "Email address to notify when in stock (or empty string if using webhook only)") String notifyEmail,
            @ToolParam(description = "Optional webhook URL for instant push: Discord webhook, ntfy.sh topic URL, or Pushover endpoint. Empty string = no webhook.") String notifyWebhook,
            @ToolParam(description = "Polling interval in seconds (min 60, typical 900 for 15 min)") double intervalSeconds,
            @ToolParam(description = "Optional price ceiling — only notify when detected price ≤ this value (same currency as site). Pass 0 to ignore price and watch stock only.") double maxPrice) {
        if (!properties.isEnabled()) {
            return "Watcher skill is disabled. Enable with app.skills.watcher.enabled=true in application.properties.";
        }
        notifier.notify("Creating watcher: " + label);
        try {
            Watcher w = new Watcher();
            w.label = label;
            w.url = url;
            w.adapter = adapter;
            w.target = target;
            w.notifyEmail = notifyEmail;
            w.notifyWebhook = notifyWebhook;
            w.intervalSeconds = (int) Math.max(properties.getMinIntervalSeconds(), Math.round(intervalSeconds));
            w.maxPrice = Math.max(0, maxPrice);
            Watcher created = service.create(w);
            String gate = created.maxPrice > 0
                    ? " in stock at or below " + String.format("%,.2f", created.maxPrice)
                    : " goes in stock";
            String channels = "";
            if (notifyEmail != null && !notifyEmail.isBlank()) channels += "email " + notifyEmail;
            if (notifyWebhook != null && !notifyWebhook.isBlank()) channels += (channels.isEmpty() ? "" : " and ") + "webhook";
            if (channels.isEmpty()) channels = "(no notification channel set!)";
            return "Created watcher " + created.id + " — will check every " + created.intervalSeconds
                    + "s and notify via " + channels + " when '" + label + "'" + gate + ".";
        } catch (IllegalArgumentException e) {
            return "Failed to create watcher: " + e.getMessage();
        } catch (Exception e) {
            return "Failed to create watcher: " + e.getMessage();
        }
    }

    @Tool(description = "List all active product/stock watchers with their last known status. "
            + "Use when the user asks 'show my watchers', 'what am I watching', 'list my stock alerts', "
            + "'what products am I tracking'.")
    public String listWatchers() {
        if (!properties.isEnabled()) return "Watcher skill is disabled.";
        try {
            List<Watcher> list = service.list();
            if (list.isEmpty()) return "No watchers configured.";
            return list.stream()
                    .map(w -> String.format("  • %s — %s — every %ds — status: %s (last checked: %s)",
                            w.id, w.label, w.intervalSeconds, w.lastStatus,
                            w.lastCheckedAt.isEmpty() ? "never" : w.lastCheckedAt))
                    .collect(Collectors.joining("\n",
                            "Watchers (" + list.size() + "):\n", ""));
        } catch (Exception e) {
            return "Failed to list watchers: " + e.getMessage();
        }
    }

    @Tool(description = "Stop/delete a product watcher by its id. Use when the user says "
            + "'stop watching the Jordan 11', 'cancel the Nike watcher', 'remove my stock alert'. "
            + "Call listWatchers first if you don't have the id.")
    public String deleteWatcher(
            @ToolParam(description = "Watcher id (from listWatchers)") String id) {
        if (!properties.isEnabled()) return "Watcher skill is disabled.";
        notifier.notify("Deleting watcher: " + id);
        try {
            service.delete(id);
            return "Deleted watcher " + id;
        } catch (IllegalArgumentException e) {
            return "Not found: " + id;
        } catch (Exception e) {
            return "Failed to delete: " + e.getMessage();
        }
    }

    @Tool(description = "Run a single check on a watcher right now (don't wait for the next tick). "
            + "Useful for testing a newly-created watcher. If already in stock, sends the notification email.")
    public String triggerWatcherNow(
            @ToolParam(description = "Watcher id") String id) {
        if (!properties.isEnabled()) return "Watcher skill is disabled.";
        notifier.notify("Triggering watcher: " + id);
        try {
            Watcher w = service.triggerNow(id);
            return "Watcher " + id + " checked — status: " + w.lastStatus;
        } catch (IllegalArgumentException e) {
            return "Not found: " + id;
        } catch (Exception e) {
            return "Failed to trigger: " + e.getMessage();
        }
    }
}
