package com.minsbot.skills.networkdiag;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class NetworkDiagService {
    private final NetworkDiagConfig.NetworkDiagProperties props;
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    public NetworkDiagService(NetworkDiagConfig.NetworkDiagProperties props) { this.props = props; }

    public Map<String, Object> ping(String host, int count) throws Exception {
        String[] cmd = WIN ? new String[]{"ping", "-n", String.valueOf(count), host}
                : new String[]{"ping", "-c", String.valueOf(count), host};
        return shellResult(cmd);
    }

    public Map<String, Object> traceroute(String host) throws Exception {
        String[] cmd = WIN ? new String[]{"tracert", "-d", "-h", "20", host}
                : new String[]{"traceroute", "-n", "-m", "20", host};
        return shellResult(cmd);
    }

    public Map<String, Object> dns(String host) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("host", host);
        try {
            long t = System.nanoTime();
            InetAddress[] addrs = InetAddress.getAllByName(host);
            long ms = (System.nanoTime() - t) / 1_000_000;
            List<String> ips = new ArrayList<>();
            for (InetAddress a : addrs) ips.add(a.getHostAddress());
            r.put("ok", true); r.put("addresses", ips); r.put("resolvedInMs", ms);
        } catch (Exception e) { r.put("ok", false); r.put("error", e.getMessage()); }
        return r;
    }

    public Map<String, Object> diagnose(String url) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", url);
        URI uri = URI.create(url.startsWith("http") ? url : "http://" + url);
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();

        result.put("dns", dns(host));
        // TCP connect
        Map<String, Object> tcp = new LinkedHashMap<>();
        tcp.put("host", host); tcp.put("port", port);
        try {
            long t = System.nanoTime();
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), props.getHttpTimeoutMs()); }
            tcp.put("ok", true); tcp.put("connectMs", (System.nanoTime() - t) / 1_000_000);
        } catch (Exception e) { tcp.put("ok", false); tcp.put("error", e.getMessage()); }
        result.put("tcp", tcp);

        // HTTP HEAD
        Map<String, Object> http = new LinkedHashMap<>();
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getHttpTimeoutMs())).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url.startsWith("http") ? url : "http://" + url))
                    .timeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            long t = System.nanoTime();
            HttpResponse<Void> resp = c.send(req, HttpResponse.BodyHandlers.discarding());
            http.put("ok", true); http.put("status", resp.statusCode());
            http.put("totalMs", (System.nanoTime() - t) / 1_000_000);
            http.put("server", String.join(",", resp.headers().allValues("server")));
        } catch (Exception e) { http.put("ok", false); http.put("error", e.getMessage()); }
        result.put("http", http);

        // Verdict
        boolean dnsOk = Boolean.TRUE.equals(((Map<?, ?>) result.get("dns")).get("ok"));
        boolean tcpOk = Boolean.TRUE.equals(((Map<?, ?>) result.get("tcp")).get("ok"));
        boolean httpOk = Boolean.TRUE.equals(((Map<?, ?>) result.get("http")).get("ok"));
        result.put("verdict", !dnsOk ? "DNS resolution failed — check internet/DNS"
                : !tcpOk ? "DNS works but TCP connect failed — host down or firewall blocking"
                : !httpOk ? "TCP works but HTTP fails — server may be unhealthy / wrong protocol"
                : "All checks passed");
        return result;
    }

    private static Map<String, Object> shellResult(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        int code = p.waitFor();
        return Map.of("ok", code == 0, "exitCode", code, "output", sb.toString().trim());
    }
}
