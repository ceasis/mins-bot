package com.minsbot.agent.tools;

import com.minsbot.agent.SitesConfigService;
import com.minsbot.agent.SitesConfigService.SiteEntry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI-callable tools for managing saved site credentials (sites_config.txt).
 */
@Component
public class SitesConfigTools {

    private final SitesConfigService sitesConfig;
    private final ToolExecutionNotifier notifier;

    public SitesConfigTools(SitesConfigService sitesConfig, ToolExecutionNotifier notifier) {
        this.sitesConfig = sitesConfig;
        this.notifier = notifier;
    }

    @Tool(description = "List all saved site credentials (description, URL, username — passwords are masked)")
    public String listSites() {
        notifier.notify("Listing saved sites...");
        List<SiteEntry> entries = sitesConfig.loadAll();
        if (entries.isEmpty()) return "No sites saved yet. Use addSite to add one.";
        StringBuilder sb = new StringBuilder("Saved sites (" + entries.size() + "):\n");
        for (int i = 0; i < entries.size(); i++) {
            SiteEntry e = entries.get(i);
            sb.append(String.format("%d. %s\n   URL: %s\n   User: %s\n",
                    i + 1, e.description(), e.url(), e.username()));
        }
        return sb.toString();
    }

    @Tool(description = "Look up saved credentials for a site by name or URL. Returns the full credentials including password.")
    public String getSiteCredentials(
            @ToolParam(description = "Site name or URL to search for") String query) {
        notifier.notify("Looking up credentials: " + query);
        List<SiteEntry> matches = sitesConfig.find(query);
        if (matches.isEmpty()) return "No saved site matching: " + query;
        StringBuilder sb = new StringBuilder();
        for (SiteEntry e : matches) {
            sb.append("Site: ").append(e.description()).append("\n");
            sb.append("URL: ").append(e.url()).append("\n");
            sb.append("Username: ").append(e.username()).append("\n");
            sb.append("Password: ").append(e.password()).append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Save a new site with its credentials to the sites config file")
    public String addSite(
            @ToolParam(description = "Short description of the site, e.g. 'Gmail' or 'Company VPN'") String description,
            @ToolParam(description = "Login URL for the site") String url,
            @ToolParam(description = "Username or email") String username,
            @ToolParam(description = "Password") String password) {
        notifier.notify("Saving site: " + description);
        return sitesConfig.add(description, url, username, password);
    }

    @Tool(description = "Remove a saved site by its description")
    public String removeSite(
            @ToolParam(description = "Description of the site to remove") String description) {
        notifier.notify("Removing site: " + description);
        return sitesConfig.remove(description);
    }
}
