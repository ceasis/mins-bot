package com.minsbot.skills.hashcalc;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HashCalcService {

    private static final List<String> ALGORITHMS = List.of("MD5", "SHA-1", "SHA-256", "SHA-512");

    public Map<String, String> hashString(String input, List<String> algorithms) {
        List<String> algs = (algorithms == null || algorithms.isEmpty()) ? ALGORITHMS : algorithms;
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String alg : algs) {
            result.put(alg, hashBytes(bytes, alg));
        }
        return result;
    }

    public Map<String, Object> hashFile(String pathStr, List<String> algorithms, long maxBytes) throws IOException {
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        Path path = Paths.get(pathStr).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Path is not a regular file");
        }
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IllegalArgumentException("File exceeds maxFileBytes limit: " + size);
        }

        List<String> algs = (algorithms == null || algorithms.isEmpty()) ? ALGORITHMS : algorithms;
        MessageDigest[] digests = new MessageDigest[algs.size()];
        for (int i = 0; i < algs.size(); i++) {
            digests[i] = getDigest(algs.get(i));
        }

        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (MessageDigest d : digests) {
                    d.update(buffer, 0, read);
                }
            }
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        for (int i = 0; i < algs.size(); i++) {
            hashes.put(algs.get(i), toHex(digests[i].digest()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path.toString());
        result.put("size", size);
        result.put("hashes", hashes);
        return result;
    }

    public boolean verify(String hashed, String algorithm, String expected) {
        return hashBytes(hashed.getBytes(StandardCharsets.UTF_8), algorithm).equalsIgnoreCase(expected.trim());
    }

    public List<String> supportedAlgorithms() {
        return ALGORITHMS;
    }

    private static String hashBytes(byte[] bytes, String algorithm) {
        MessageDigest md = getDigest(algorithm);
        return toHex(md.digest(bytes));
    }

    private static MessageDigest getDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
