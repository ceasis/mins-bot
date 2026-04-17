package com.minsbot.skills.randomgen;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RandomGenService {

    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?";

    private final SecureRandom random = new SecureRandom();

    public List<String> uuids(int count) {
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(UUID.randomUUID().toString());
        }
        return out;
    }

    public List<Long> integers(int count, long min, long max) {
        if (min > max) throw new IllegalArgumentException("min must be <= max");
        List<Long> out = new ArrayList<>(count);
        long range = max - min + 1;
        for (int i = 0; i < count; i++) {
            long r = Math.abs(random.nextLong() % range);
            out.add(min + r);
        }
        return out;
    }

    public List<Integer> dice(int count, int sides) {
        if (sides < 2) throw new IllegalArgumentException("sides must be >= 2");
        List<Integer> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(random.nextInt(sides) + 1);
        }
        return out;
    }

    public List<String> passwords(int count, int length, boolean upper, boolean digits, boolean symbols) {
        if (length < 4) throw new IllegalArgumentException("length must be >= 4");
        StringBuilder pool = new StringBuilder(LOWER);
        if (upper) pool.append(UPPER);
        if (digits) pool.append(DIGITS);
        if (symbols) pool.append(SYMBOLS);
        String poolStr = pool.toString();

        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder(length);
            for (int j = 0; j < length; j++) {
                sb.append(poolStr.charAt(random.nextInt(poolStr.length())));
            }
            out.add(sb.toString());
        }
        return out;
    }

    public List<String> strings(int count, int length, String charset) {
        if (charset == null || charset.isEmpty()) {
            throw new IllegalArgumentException("charset must not be empty");
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder(length);
            for (int j = 0; j < length; j++) {
                sb.append(charset.charAt(random.nextInt(charset.length())));
            }
            out.add(sb.toString());
        }
        return out;
    }

    public List<String> choose(List<String> options, int count, boolean unique) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty");
        }
        if (unique && count > options.size()) {
            throw new IllegalArgumentException("count > options.size() with unique=true");
        }
        List<String> source = unique ? new ArrayList<>(options) : options;
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = random.nextInt(source.size());
            out.add(source.get(idx));
            if (unique) source.remove(idx);
        }
        return out;
    }
}
