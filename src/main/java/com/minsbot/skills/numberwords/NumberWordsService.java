package com.minsbot.skills.numberwords;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class NumberWordsService {

    private static final String[] ONES = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final String[] SCALE = {"", "thousand", "million", "billion", "trillion"};

    private static final String[] ROMAN_VALUES = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
    private static final int[] ROMAN_NUMS = {1000,900,500,400,100,90,50,40,10,9,5,4,1};

    public String toWords(long number) {
        if (number == 0) return "zero";
        if (number < 0) return "negative " + toWords(-number);
        StringBuilder sb = new StringBuilder();
        int scaleIdx = 0;
        while (number > 0) {
            int chunk = (int) (number % 1000);
            if (chunk != 0) {
                String chunkWords = chunkToWords(chunk);
                if (sb.length() > 0) sb.insert(0, " ");
                if (scaleIdx > 0) sb.insert(0, SCALE[scaleIdx] + (chunkWords.isEmpty() ? "" : " "));
                sb.insert(0, chunkWords);
            }
            number /= 1000;
            scaleIdx++;
        }
        return sb.toString().trim();
    }

    public long fromWords(String words) {
        if (words == null || words.isBlank()) throw new IllegalArgumentException("words required");
        Map<String, Long> small = new HashMap<>();
        for (int i = 0; i < ONES.length; i++) if (!ONES[i].isEmpty()) small.put(ONES[i], (long) i);
        for (int i = 2; i < TENS.length; i++) small.put(TENS[i], (long) (i * 10));
        small.put("hundred", 100L);
        Map<String, Long> scales = Map.of("thousand", 1000L, "million", 1_000_000L, "billion", 1_000_000_000L, "trillion", 1_000_000_000_000L);

        long total = 0, current = 0;
        for (String raw : words.toLowerCase().replace("-", " ").replace(",", " ").split("\\s+")) {
            if (raw.equals("and") || raw.isEmpty()) continue;
            if (small.containsKey(raw)) {
                long v = small.get(raw);
                if (v == 100) current *= 100;
                else current += v;
            } else if (scales.containsKey(raw)) {
                current *= scales.get(raw);
                total += current;
                current = 0;
            } else throw new IllegalArgumentException("unknown word: " + raw);
        }
        return total + current;
    }

    public String toRoman(int n) {
        if (n < 1 || n > 3999) throw new IllegalArgumentException("roman range 1-3999");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ROMAN_NUMS.length; i++) {
            while (n >= ROMAN_NUMS[i]) { sb.append(ROMAN_VALUES[i]); n -= ROMAN_NUMS[i]; }
        }
        return sb.toString();
    }

    public int fromRoman(String roman) {
        if (roman == null || roman.isBlank()) throw new IllegalArgumentException("roman required");
        roman = roman.toUpperCase();
        int total = 0;
        for (int i = 0; i < roman.length(); i++) {
            int val = valOf(roman.charAt(i));
            if (i + 1 < roman.length() && val < valOf(roman.charAt(i + 1))) total -= val;
            else total += val;
        }
        return total;
    }

    private static int valOf(char c) {
        return switch (c) { case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100; case 'D' -> 500; case 'M' -> 1000; default -> throw new IllegalArgumentException("invalid roman: " + c); };
    }

    private static String chunkToWords(int n) {
        StringBuilder sb = new StringBuilder();
        if (n >= 100) { sb.append(ONES[n / 100]).append(" hundred"); n %= 100; if (n > 0) sb.append(" "); }
        if (n >= 20) { sb.append(TENS[n / 10]); n %= 10; if (n > 0) sb.append("-").append(ONES[n]); }
        else if (n > 0) sb.append(ONES[n]);
        return sb.toString();
    }
}
