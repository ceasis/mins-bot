package com.minsbot.skills.watcher.adapters;

import com.minsbot.agent.tools.PlaywrightService;
import com.minsbot.skills.watcher.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nike Philippines product size availability adapter.
 *
 * Strategy: render the product page with Playwright (site is behind Akamai — plain HTTP
 * returns 403), then look at Nike's embedded product JSON for size/availability.
 *
 * Nike PDPs embed a __NEXT_DATA__ JSON blob that contains a `skus` array with
 * { nikeSize, available } entries. We match by normalized size string.
 */
@Component
public class NikePhAdapter implements WatcherAdapter {

    private static final Logger log = LoggerFactory.getLogger(NikePhAdapter.class);

    private final PlaywrightService playwright;

    public NikePhAdapter(PlaywrightService playwright) {
        this.playwright = playwright;
    }

    @Override
    public String name() { return "nike-ph"; }

    @Override
    public CheckResult check(Watcher w) {
        String targetSize = normalize(w.target);
        if (targetSize.isEmpty()) return CheckResult.error("target size is empty");

        String html;
        try {
            html = playwright.getPageHtml(w.url);
        } catch (Exception e) {
            log.warn("[NikePhAdapter] Playwright fetch failed for {}: {}", w.url, e.getMessage());
            return CheckResult.error("fetch failed: " + e.getMessage());
        }
        if (html == null || html.isBlank()) return CheckResult.error("empty response");

        // Look for size entries in the embedded product JSON. Nike's shape varies but
        // every SKU appears as an object with "nikeSize" and "available" fields.
        // Match: "nikeSize":"9.5", ... "available":true (within ~400 chars, either order).
        Pattern sku = Pattern.compile(
                "\"nikeSize\"\\s*:\\s*\"([^\"]+)\"[^{}]{0,400}?\"available\"\\s*:\\s*(true|false)"
                        + "|\"available\"\\s*:\\s*(true|false)[^{}]{0,400}?\"nikeSize\"\\s*:\\s*\"([^\"]+)\"");

        Matcher m = sku.matcher(html);
        int total = 0;
        boolean foundSize = false;
        boolean available = false;
        while (m.find()) {
            total++;
            String size = m.group(1) != null ? m.group(1) : m.group(4);
            String avail = m.group(2) != null ? m.group(2) : m.group(3);
            if (normalize(size).equals(targetSize)) {
                foundSize = true;
                available = "true".equalsIgnoreCase(avail);
                break;
            }
        }

        if (total == 0) {
            // Nike may have changed schema or returned a bot-challenge page.
            return CheckResult.unknown("no SKU data found in page (schema change or challenge?)");
        }
        if (!foundSize) {
            return CheckResult.outOfStock("size " + w.target + " not listed (total sizes: " + total + ")");
        }
        if (!available) {
            return CheckResult.outOfStock("size " + w.target + " is sold out");
        }

        // Price gate: if maxPrice set, only report in-stock when currentPrice <= maxPrice.
        double price = extractCurrentPrice(html);
        String priceStr = price > 0 ? "₱" + String.format("%,.2f", price) : "(price unknown)";
        if (w.maxPrice > 0) {
            if (price <= 0) {
                return CheckResult.unknown("size " + w.target + " available but price could not be parsed");
            }
            if (price > w.maxPrice) {
                return CheckResult.outOfStock("size " + w.target + " available at " + priceStr
                        + " but above maxPrice ₱" + String.format("%,.2f", w.maxPrice));
            }
            return CheckResult.inStock("size " + w.target + " available at " + priceStr
                    + " (≤ maxPrice ₱" + String.format("%,.2f", w.maxPrice) + ")");
        }
        return CheckResult.inStock("size " + w.target + " available at " + priceStr);
    }

    /**
     * Extract the lowest "currentPrice" numeric value from the embedded product JSON.
     * Returns 0 if not found. Nike pages typically have the price in multiple places;
     * taking the min guards against promotional/list-price confusion.
     */
    private static double extractCurrentPrice(String html) {
        Pattern priceP = Pattern.compile("\"currentPrice\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
        Matcher m = priceP.matcher(html);
        double min = Double.MAX_VALUE;
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if (v > 0 && v < min) min = v;
            } catch (NumberFormatException ignored) {}
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().replace(",", ".").replaceAll("\\s+", "");
    }
}
