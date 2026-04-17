package com.minsbot.skills.jwtinspector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtInspectorService {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> decode(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Not a JWT (expected 2 or 3 dot-separated parts)");

        Map<String, Object> header = readJson(parts[0]);
        Map<String, Object> payload = readJson(parts[1]);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("header", header);
        out.put("payload", payload);
        out.put("signaturePresent", parts.length == 3 && !parts[2].isEmpty());
        out.put("alg", header.get("alg"));
        out.put("typ", header.get("typ"));

        Object exp = payload.get("exp");
        if (exp instanceof Number n) {
            long expSec = n.longValue();
            Instant when = Instant.ofEpochSecond(expSec);
            out.put("expiresAt", when.toString());
            out.put("expired", Instant.now().isAfter(when));
        }
        Object iat = payload.get("iat");
        if (iat instanceof Number n) out.put("issuedAt", Instant.ofEpochSecond(n.longValue()).toString());
        Object nbf = payload.get("nbf");
        if (nbf instanceof Number n) {
            Instant when = Instant.ofEpochSecond(n.longValue());
            out.put("notBefore", when.toString());
            out.put("notYetValid", Instant.now().isBefore(when));
        }
        return out;
    }

    public Map<String, Object> verifyHmac(String token, String secret) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Need 3 parts to verify");
        Map<String, Object> header = readJson(parts[0]);
        String alg = String.valueOf(header.get("alg"));
        String macAlg = switch (alg) {
            case "HS256" -> "HmacSHA256";
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> throw new IllegalArgumentException("Not an HMAC algorithm: " + alg);
        };

        Mac mac = Mac.getInstance(macAlg);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlg));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        byte[] actual = Base64.getUrlDecoder().decode(parts[2]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", alg);
        result.put("valid", MessageDigest.isEqual(expected, actual));
        return result;
    }

    public Map<String, Object> verifyRsa(String token, String publicKeyPem) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Need 3 parts to verify");
        Map<String, Object> header = readJson(parts[0]);
        String alg = String.valueOf(header.get("alg"));
        String sigAlg = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            default -> throw new IllegalArgumentException("Not an RSA algorithm: " + alg);
        };

        String cleaned = publicKeyPem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "").replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleaned);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(keyBytes));

        Signature sig = Signature.getInstance(sigAlg);
        sig.initVerify(pub);
        sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        boolean valid = sig.verify(Base64.getUrlDecoder().decode(parts[2]));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", alg);
        result.put("valid", valid);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String urlBase64) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(padBase64(urlBase64));
        return mapper.readValue(decoded, Map.class);
    }

    private static String padBase64(String s) {
        int rem = s.length() % 4;
        if (rem == 0) return s;
        return s + "====".substring(rem);
    }
}
