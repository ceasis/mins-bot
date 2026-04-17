package com.minsbot.skills.certinspector;

import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class CertInspectorService {

    private final CertInspectorConfig.CertInspectorProperties properties;

    public CertInspectorService(CertInspectorConfig.CertInspectorProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> inspect(String host, int port) throws IOException {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range");

        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new PermissiveTrustManager()}, new java.security.SecureRandom());
        } catch (Exception e) {
            throw new IOException("SSL context setup failed: " + e.getMessage());
        }

        SSLSocketFactory factory = ctx.getSocketFactory();
        try (Socket raw = new Socket();
             SSLSocket socket = (SSLSocket) factory.createSocket(raw, host, port, true)) {
            raw.connect(new java.net.InetSocketAddress(host, port), properties.getTimeoutMs());
            socket.setSoTimeout(properties.getTimeoutMs());
            socket.startHandshake();

            SSLSession session = socket.getSession();
            Certificate[] chain = session.getPeerCertificates();
            List<Map<String, Object>> chainInfo = new ArrayList<>();
            for (Certificate c : chain) {
                if (c instanceof X509Certificate x) chainInfo.add(certInfo(x));
            }

            X509Certificate leaf = (X509Certificate) chain[0];
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("host", host);
            result.put("port", port);
            result.put("protocol", session.getProtocol());
            result.put("cipherSuite", session.getCipherSuite());
            result.put("leaf", certInfo(leaf));
            result.put("chainLength", chain.length);
            result.put("chain", chainInfo);
            return result;
        }
    }

    private static Map<String, Object> certInfo(X509Certificate c) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("subject", c.getSubjectX500Principal().getName());
        info.put("issuer", c.getIssuerX500Principal().getName());
        info.put("serialNumber", c.getSerialNumber().toString(16));
        info.put("version", c.getVersion());
        info.put("sigAlg", c.getSigAlgName());
        info.put("notBefore", c.getNotBefore().toInstant().toString());
        info.put("notAfter", c.getNotAfter().toInstant().toString());
        Instant now = Instant.now();
        Instant expires = c.getNotAfter().toInstant();
        long daysRemaining = Duration.between(now, expires).toDays();
        info.put("daysUntilExpiry", daysRemaining);
        info.put("expired", now.isAfter(expires));
        info.put("expiresSoon", daysRemaining >= 0 && daysRemaining < 30);
        try {
            Collection<List<?>> sans = c.getSubjectAlternativeNames();
            if (sans != null) {
                List<String> dns = new ArrayList<>();
                for (List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                        dns.add(String.valueOf(san.get(1)));
                    }
                }
                info.put("subjectAltNames", dns);
            }
        } catch (Exception ignored) {}
        return info;
    }

    /** Accepts any chain — we're inspecting, not trusting. */
    private static class PermissiveTrustManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
