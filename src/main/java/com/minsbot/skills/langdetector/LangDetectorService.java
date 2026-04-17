package com.minsbot.skills.langdetector;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class LangDetectorService {

    // Script-based detection (fast, high-signal)
    public Map<String, Object> detect(String text) {
        if (text == null) text = "";
        Map<String, Integer> scriptHits = new LinkedHashMap<>();
        int cjk = 0, hiragana = 0, katakana = 0, hangul = 0, arabic = 0, cyrillic = 0, hebrew = 0, greek = 0,
            devanagari = 0, thai = 0, latin = 0, total = 0;
        for (int i = 0; i < text.length(); i++) {
            int c = text.codePointAt(i);
            if (Character.isLetter(c)) {
                total++;
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                if (block == Character.UnicodeBlock.HIRAGANA) hiragana++;
                else if (block == Character.UnicodeBlock.KATAKANA) katakana++;
                else if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                        || block == Character.UnicodeBlock.HANGUL_JAMO) hangul++;
                else if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) cjk++;
                else if (block == Character.UnicodeBlock.ARABIC) arabic++;
                else if (block == Character.UnicodeBlock.CYRILLIC) cyrillic++;
                else if (block == Character.UnicodeBlock.HEBREW) hebrew++;
                else if (block == Character.UnicodeBlock.GREEK) greek++;
                else if (block == Character.UnicodeBlock.DEVANAGARI) devanagari++;
                else if (block == Character.UnicodeBlock.THAI) thai++;
                else if (block == Character.UnicodeBlock.BASIC_LATIN
                        || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                        || block == Character.UnicodeBlock.LATIN_EXTENDED_A
                        || block == Character.UnicodeBlock.LATIN_EXTENDED_B) latin++;
            }
        }
        scriptHits.put("latin", latin);
        scriptHits.put("cjk", cjk);
        scriptHits.put("hiragana", hiragana);
        scriptHits.put("katakana", katakana);
        scriptHits.put("hangul", hangul);
        scriptHits.put("arabic", arabic);
        scriptHits.put("cyrillic", cyrillic);
        scriptHits.put("hebrew", hebrew);
        scriptHits.put("greek", greek);
        scriptHits.put("devanagari", devanagari);
        scriptHits.put("thai", thai);

        String primaryScript = scriptHits.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("unknown");
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (total == 0) candidates.add(Map.of("lang", "unknown", "confidence", 0.0));
        else if (hiragana + katakana > 0) candidates.add(Map.of("lang", "ja", "confidence", round((double) (hiragana + katakana + cjk) / total)));
        else if (hangul > 0) candidates.add(Map.of("lang", "ko", "confidence", round((double) hangul / total)));
        else if (cjk > 0) candidates.add(Map.of("lang", "zh", "confidence", round((double) cjk / total)));
        else if (arabic > 0) candidates.add(Map.of("lang", "ar", "confidence", round((double) arabic / total)));
        else if (hebrew > 0) candidates.add(Map.of("lang", "he", "confidence", round((double) hebrew / total)));
        else if (greek > 0) candidates.add(Map.of("lang", "el", "confidence", round((double) greek / total)));
        else if (cyrillic > 0) candidates.add(Map.of("lang", "ru", "confidence", round((double) cyrillic / total)));
        else if (thai > 0) candidates.add(Map.of("lang", "th", "confidence", round((double) thai / total)));
        else if (devanagari > 0) candidates.add(Map.of("lang", "hi", "confidence", round((double) devanagari / total)));
        else if (latin > 0) {
            candidates.addAll(latinCandidates(text));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primaryScript", primaryScript);
        out.put("scriptCounts", scriptHits);
        out.put("totalLetters", total);
        out.put("candidates", candidates);
        return out;
    }

    /** Simple stopword-based heuristic for Latin-script languages */
    private static List<Map<String, Object>> latinCandidates(String text) {
        String lower = text.toLowerCase();
        Map<String, Set<String>> langStopwords = Map.of(
                "en", Set.of("the","and","of","to","in","is","that","for","it","with","as","on","was","you","this"),
                "es", Set.of("el","la","de","que","y","los","las","en","un","una","es","por","con","para","se"),
                "fr", Set.of("le","la","de","et","les","des","à","un","une","est","pour","dans","qui","pas","sur"),
                "de", Set.of("der","die","das","und","zu","den","nicht","von","sie","ist","des","mit","dem","auf"),
                "it", Set.of("il","di","che","e","la","per","un","in","una","sono","con","non","si","del","da"),
                "pt", Set.of("o","a","de","que","e","do","da","em","um","uma","para","com","não","os","no")
        );
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : langStopwords.entrySet()) {
            int hits = 0;
            for (String sw : e.getValue()) {
                int idx = 0;
                while ((idx = lower.indexOf(" " + sw + " ", idx)) != -1) { hits++; idx++; }
            }
            if (hits > 0) results.add(Map.of("lang", e.getKey(), "hits", hits));
        }
        results.sort((a, b) -> Integer.compare((int) b.get("hits"), (int) a.get("hits")));
        return results.isEmpty() ? List.of(Map.of("lang", "en", "confidence", 0.5)) : results;
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
