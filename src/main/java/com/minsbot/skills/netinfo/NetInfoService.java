package com.minsbot.skills.netinfo;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class NetInfoService {

    private final NetInfoConfig.NetInfoProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public NetInfoService(NetInfoConfig.NetInfoProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> hostInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            InetAddress local = InetAddress.getLocalHost();
            out.put("hostname", local.getHostName());
            out.put("localAddress", local.getHostAddress());
        } catch (UnknownHostException e) {
            out.put("hostname", "unknown");
            out.put("localAddress", null);
        }
        return out;
    }

    public List<Map<String, Object>> interfaces() throws SocketException {
        List<Map<String, Object>> out = new ArrayList<>();
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces.hasMoreElements()) {
            NetworkInterface nic = ifaces.nextElement();
            if (!nic.isUp()) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", nic.getName());
            info.put("displayName", nic.getDisplayName());
            info.put("mtu", nic.getMTU());
            info.put("loopback", nic.isLoopback());
            info.put("virtual", nic.isVirtual());
            byte[] mac = nic.getHardwareAddress();
            if (mac != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mac.length; i++) {
                    sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
                }
                info.put("mac", sb.toString());
            }
            List<String> addrs = new ArrayList<>();
            for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                addrs.add(ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength());
            }
            info.put("addresses", addrs);
            out.add(info);
        }
        return out;
    }

    public String publicIp() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(properties.getPublicIpUrl()))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Public IP service returned " + resp.statusCode());
        }
        return resp.body().trim();
    }

    public Map<String, Object> checkPort(String host, int port) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");
        List<String> allowed = properties.getAllowedPortCheckHosts();
        if (!allowed.isEmpty() && !allowed.contains(host)) {
            throw new IllegalArgumentException("host not in allowedPortCheckHosts: " + host);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host);
        out.put("port", port);
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), properties.getPortCheckTimeoutMs());
            out.put("open", true);
            out.put("elapsedMs", System.currentTimeMillis() - start);
        } catch (IOException e) {
            out.put("open", false);
            out.put("elapsedMs", System.currentTimeMillis() - start);
            out.put("reason", e.getClass().getSimpleName());
        }
        return out;
    }

    public Map<String, Object> resolveHost(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        InetAddress[] addrs = InetAddress.getAllByName(host);
        List<String> list = new ArrayList<>();
        for (InetAddress a : addrs) list.add(a.getHostAddress());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host", host);
        out.put("addresses", list);
        return out;
    }
}
