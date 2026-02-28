package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Network management tools for WiFi, connection diagnostics, and network configuration.
 */
@Component
public class NetworkTools {

    private final SystemControlService systemControl;
    private final ToolExecutionNotifier notifier;

    public NetworkTools(SystemControlService systemControl, ToolExecutionNotifier notifier) {
        this.systemControl = systemControl;
        this.notifier = notifier;
    }

    @Tool(description = "List available WiFi networks with signal strength and security type. "
            + "Shows SSIDs, signal quality, and authentication method.")
    public String listWifiNetworks() {
        notifier.notify("Scanning WiFi networks...");
        return systemControl.runCmd("netsh wlan show networks mode=bssid");
    }

    @Tool(description = "Connect to a WiFi network by SSID and password. "
            + "Creates a temporary WiFi profile and connects to the network. "
            + "Example: connectToWifi('MyHomeWiFi', 'password123')")
    public String connectToWifi(
            @ToolParam(description = "The WiFi network name (SSID) to connect to") String ssid,
            @ToolParam(description = "The WiFi password") String password) {
        notifier.notify("Connecting to WiFi: " + ssid + "...");
        String safeSsid = sanitize(ssid);
        String safePass = sanitize(password);

        // Create a temporary WiFi profile XML and connect
        String profileXml = "<?xml version=\\\"1.0\\\"?>"
                + "<WLANProfile xmlns=\\\"http://www.microsoft.com/networking/WLAN/profile/v1\\\">"
                + "<name>" + safeSsid + "</name>"
                + "<SSIDConfig><SSID><name>" + safeSsid + "</name></SSID></SSIDConfig>"
                + "<connectionType>ESS</connectionType><connectionMode>auto</connectionMode>"
                + "<MSM><security><authEncryption>"
                + "<authentication>WPA2PSK</authentication><encryption>AES</encryption>"
                + "<useOneX>false</useOneX></authEncryption>"
                + "<sharedKey><keyType>passPhrase</keyType><protected>false</protected>"
                + "<keyMaterial>" + safePass + "</keyMaterial></sharedKey>"
                + "</security></MSM></WLANProfile>";

        String tempFile = System.getenv("TEMP") + "\\wifi_profile.xml";
        String cmd = "echo " + profileXml + " > \"" + tempFile + "\" && "
                + "netsh wlan add profile filename=\"" + tempFile + "\" && "
                + "netsh wlan connect name=\"" + safeSsid + "\" && "
                + "del \"" + tempFile + "\"";
        return systemControl.runCmd(cmd);
    }

    @Tool(description = "Disconnect from the current WiFi network.")
    public String disconnectWifi() {
        notifier.notify("Disconnecting WiFi...");
        return systemControl.runCmd("netsh wlan disconnect");
    }

    @Tool(description = "Show current WiFi connection status including SSID, signal strength, and connection speed.")
    public String getWifiStatus() {
        notifier.notify("Checking WiFi status...");
        return systemControl.runCmd("netsh wlan show interfaces");
    }

    @Tool(description = "Get detailed network information including IP addresses, adapters, DNS servers, and connection status.")
    public String getNetworkInfo() {
        notifier.notify("Getting network info...");
        return systemControl.runPowerShell(
                "Get-NetAdapter | Where-Object Status -eq 'Up' | "
                + "Select-Object Name, InterfaceDescription, LinkSpeed, MacAddress | Format-Table -AutoSize; "
                + "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.PrefixOrigin -ne 'WellKnown' } | "
                + "Select-Object InterfaceAlias, IPAddress | Format-Table -AutoSize");
    }

    @Tool(description = "Look up the IP address of a domain name (DNS lookup). "
            + "Example: dnsLookup('google.com')")
    public String dnsLookup(
            @ToolParam(description = "Domain name to look up, e.g. 'google.com'") String hostname) {
        notifier.notify("DNS lookup: " + hostname + "...");
        return systemControl.runCmd("nslookup " + sanitize(hostname));
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replaceAll("[\"&|<>]", "");
    }
}
