package com.minsbot.skills.encoder;

import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class EncoderService {

    public String base64Encode(String input) {
        return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    public String base64Decode(String input) {
        byte[] decoded = Base64.getDecoder().decode(input);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    public String base64UrlEncode(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    public String base64UrlDecode(String input) {
        byte[] decoded = Base64.getUrlDecoder().decode(input);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    public String hexEncode(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String hexDecode(String input) {
        String clean = input.replaceAll("\\s+", "");
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character at position " + (i * 2));
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String urlEncode(String input) {
        return URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    public String urlDecode(String input) {
        return URLDecoder.decode(input, StandardCharsets.UTF_8);
    }
}
