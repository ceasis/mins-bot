package com.minsbot.skills.vpncheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Detects active VPN by interface name heuristics + reports public IP/geo.
 */
@Service
public class VpnCheckService {
    private final VpnCheckConfig.VpnCheckProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final List<String> VPN_HINTS = List.of("tap", "tun", "wireguard", "wg", "openvpn",
            "expressvpn", "nordlynx", "nordvpn", "surfshark", "mullvad", "tailscale", "zerotier",
            "cisco", "anyconnect", "pulsesecure", "forticlient", "wgvpn", "ovpn");

    public VpnCheckService(VpnCheckConfig.VpnCheckProperties props) { this.props = props; }

    public Map<String, Object> check() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        // 1. Find network interfaces with VPN-like names that have an IP
        List<Map<String, Object>> vpnIfaces = new ArrayList<>();
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
        while (nics.hasMoreElements()) {
            NetworkInterface n = nics.nextElement();
            if (!n.isUp() || n.isLoopback()) continue;
            String name = n.getName().toLowerCase(Locale.ROOT);
            String dn = (n.getDisplayName() == null ? "" : n.getDisplayName().toLowerCase(Locale.ROOT));
            boolean isVpn = VPN_HINTS.stream().anyMatch(h -> name.contains(h) || dn.contains(h));
            if (isVpn) {
                List<String> ips = new ArrayList<>();
                Enumeration<InetAddress> addrs = n.getInetAddresses();
                while (addrs.hasMoreElements()) ips.add(addrs.nextElement().getHostAddress());
                vpnIfaces.add(Map.of("name", n.getName(), "displayName", n.getDisplayName(), "addresses", ips));
            }
        }
        result.put("vpnInterfaces", vpnIfaces);
        result.put("vpnDetected", !vpnIfaces.isEmpty());

        // 2. Public IP + geo (api.ipify.org for IP, ip-api.com for geo)
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getTimeoutMs())).build();
            String ip = c.send(HttpRequest.newBuilder().uri(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofMillis(props.getTimeoutMs())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().trim();
            result.put("publicIp", ip);
            try {
                String geoBody = c.send(HttpRequest.newBuilder().uri(URI.create("http://ip-api.com/json/" + ip))
                        .timeout(Duration.ofMillis(props.getTimeoutMs())).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                Map<String, Object> geo = mapper.readValue(geoBody, Map.class);
                result.put("geo", Map.of(
                        "country", geo.getOrDefault("country", ""),
                        "region", geo.getOrDefault("regionName", ""),
                        "city", geo.getOrDefault("city", ""),
                        "isp", geo.getOrDefault("isp", ""),
                        "org", geo.getOrDefault("org", "")));
            } catch (Exception e) { result.put("geoError", e.getMessage()); }
        } catch (Exception e) { result.put("publicIpError", e.getMessage()); }

        // 3. DNS leak check (best-effort: which DNS server is system using?)
        try {
            if (WIN) {
                String out = run("ipconfig", "/all");
                List<String> dnsServers = new ArrayList<>();
                for (String line : out.split("\\R")) {
                    if (line.contains("DNS Servers")) {
                        int i = line.indexOf(':'); if (i > 0) dnsServers.add(line.substring(i + 1).trim());
                    }
                }
                result.put("dnsServers", dnsServers);
            } else {
                String out = run("cat", "/etc/resolv.conf");
                List<String> dns = new ArrayList<>();
                for (String l : out.split("\\R")) if (l.startsWith("nameserver")) dns.add(l.substring(10).trim());
                result.put("dnsServers", dns);
            }
        } catch (Exception ignored) {}

        return result;
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }
}
