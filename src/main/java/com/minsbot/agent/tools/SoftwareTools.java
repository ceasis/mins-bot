package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Software management tools using Windows Package Manager (winget).
 * Covers: install, uninstall, search, list, and system updates.
 */
@Component
public class SoftwareTools {

    private final SystemControlService systemControl;
    private final ToolExecutionNotifier notifier;

    public SoftwareTools(SystemControlService systemControl, ToolExecutionNotifier notifier) {
        this.systemControl = systemControl;
        this.notifier = notifier;
    }

    @Tool(description = "Search for available software packages by name using winget. "
            + "Returns matching packages with their ID and version. "
            + "Example: searchSoftware('vlc') finds VideoLAN.VLC")
    public String searchSoftware(
            @ToolParam(description = "Software name or keyword to search for") String query) {
        notifier.notify("Searching for software: " + query + "...");
        return systemControl.runCmd("winget search \"" + sanitize(query) + "\" --accept-source-agreements");
    }

    @Tool(description = "Install software on the computer using winget (Windows Package Manager). "
            + "Use searchSoftware first to find the exact package ID. "
            + "Example: installSoftware('VideoLAN.VLC') installs VLC media player.")
    public String installSoftware(
            @ToolParam(description = "Package name or ID to install, e.g. 'VideoLAN.VLC', 'Google.Chrome', '7zip.7zip'") String packageName) {
        notifier.notify("Installing " + packageName + "...");
        return systemControl.runCmd("winget install \"" + sanitize(packageName)
                + "\" --accept-source-agreements --accept-package-agreements");
    }

    @Tool(description = "Uninstall software from the computer using winget. "
            + "Example: uninstallSoftware('VideoLAN.VLC') removes VLC.")
    public String uninstallSoftware(
            @ToolParam(description = "Package name or ID to uninstall") String packageName) {
        notifier.notify("Uninstalling " + packageName + "...");
        return systemControl.runCmd("winget uninstall \"" + sanitize(packageName) + "\"");
    }

    @Tool(description = "List all installed software on the computer. Returns package names, IDs, and versions.")
    public String listInstalledSoftware() {
        notifier.notify("Listing installed software...");
        return systemControl.runCmd("winget list --accept-source-agreements");
    }

    @Tool(description = "Check for available software updates. Shows which installed packages have newer versions available.")
    public String checkForUpdates() {
        notifier.notify("Checking for updates...");
        return systemControl.runCmd("winget upgrade --accept-source-agreements");
    }

    @Tool(description = "Install all available software updates via winget. This may take several minutes.")
    public String installAllUpdates() {
        notifier.notify("Installing all available updates...");
        return systemControl.runCmd("winget upgrade --all --accept-source-agreements --accept-package-agreements");
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[\"&|<>]", "");
    }
}
