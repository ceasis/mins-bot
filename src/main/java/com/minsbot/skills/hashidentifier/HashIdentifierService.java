package com.minsbot.skills.hashidentifier;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class HashIdentifierService {

    private record HashSig(String name, int length, Pattern pattern, String notes) {}

    private static final List<HashSig> SIGNATURES = List.of(
            new HashSig("MD5",        32, Pattern.compile("^[a-f0-9]{32}$", Pattern.CASE_INSENSITIVE), "Also: NTLM, MD4, LM"),
            new HashSig("NTLM",       32, Pattern.compile("^[a-f0-9]{32}$", Pattern.CASE_INSENSITIVE), "Windows NT hash — indistinguishable from MD5 by format alone"),
            new HashSig("SHA-1",      40, Pattern.compile("^[a-f0-9]{40}$", Pattern.CASE_INSENSITIVE), "Also: RIPEMD-160, MySQL4.1"),
            new HashSig("SHA-224",    56, Pattern.compile("^[a-f0-9]{56}$", Pattern.CASE_INSENSITIVE), null),
            new HashSig("SHA-256",    64, Pattern.compile("^[a-f0-9]{64}$", Pattern.CASE_INSENSITIVE), "Also: SHA3-256, Keccak-256"),
            new HashSig("SHA-384",    96, Pattern.compile("^[a-f0-9]{96}$", Pattern.CASE_INSENSITIVE), null),
            new HashSig("SHA-512",   128, Pattern.compile("^[a-f0-9]{128}$", Pattern.CASE_INSENSITIVE), "Also: SHA3-512, Whirlpool"),
            new HashSig("CRC32",       8, Pattern.compile("^[a-f0-9]{8}$",  Pattern.CASE_INSENSITIVE), "Not cryptographic"),
            new HashSig("MySQL323",   16, Pattern.compile("^[a-f0-9]{16}$", Pattern.CASE_INSENSITIVE), "Legacy MySQL"),
            new HashSig("bcrypt",      0, Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$"), "$2a$/$2b$/$2y$ prefix"),
            new HashSig("Argon2",      0, Pattern.compile("^\\$argon2(id|i|d)\\$.+"), "Argon2 — modern password hash"),
            new HashSig("scrypt",      0, Pattern.compile("^\\$7\\$.+"), "$7$ prefix"),
            new HashSig("PBKDF2",      0, Pattern.compile("^\\$pbkdf2(-sha(1|256|512))?\\$.+"), null),
            new HashSig("SHA-crypt",   0, Pattern.compile("^\\$(5|6)\\$(?:rounds=\\d+\\$)?[^$]+\\$.+"), "$5$ SHA-256, $6$ SHA-512 (crypt)"),
            new HashSig("MD5-crypt",   0, Pattern.compile("^\\$1\\$[^$]+\\$.+"), null),
            new HashSig("Django-PBKDF2",0, Pattern.compile("^pbkdf2_sha(1|256|512)\\$\\d+\\$.+"), null),
            new HashSig("MD5-APR1",    0, Pattern.compile("^\\$apr1\\$[^$]+\\$.+"), "Apache htpasswd"),
            new HashSig("phpass",      0, Pattern.compile("^\\$[HP]\\$[./A-Za-z0-9]{31}$"), "phpBB/WordPress"),
            new HashSig("DES (crypt)", 13, Pattern.compile("^[./A-Za-z0-9]{13}$"), "Traditional Unix crypt"),
            new HashSig("JWT",         0, Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"), "Looks like a JWT token"),
            new HashSig("Base64",      0, Pattern.compile("^[A-Za-z0-9+/]+={0,2}$"), "Likely base64 encoded (not a hash)")
    );

    public Map<String, Object> identify(String input) {
        if (input == null) input = "";
        String trimmed = input.trim();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (HashSig sig : SIGNATURES) {
            if (sig.length() > 0 && trimmed.length() != sig.length()) continue;
            if (sig.pattern().matcher(trimmed).matches()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", sig.name());
                if (sig.length() > 0) c.put("length", sig.length());
                if (sig.notes() != null) c.put("notes", sig.notes());
                candidates.add(c);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", trimmed);
        result.put("length", trimmed.length());
        result.put("candidateCount", candidates.size());
        result.put("candidates", candidates);
        return result;
    }
}
