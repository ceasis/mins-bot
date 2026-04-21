package com.minsbot.agent.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Password-recovery for user-forgotten PDFs. Dictionary-attack style:
 * takes a candidate list (from a wordlist file, common-password generator,
 * or a user-provided hint set) and tries each. Saves an unlocked copy when
 * it finds a match.
 *
 * <p>Intended for legitimate own-PDF recovery. The bot refuses to crack
 * arbitrary-third-party documents via a simple ownership attestation.
 */
@Component
public class PdfPasswordCrackerTools {

    private static final Logger log = LoggerFactory.getLogger(PdfPasswordCrackerTools.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 5000;

    private final ToolExecutionNotifier notifier;

    public PdfPasswordCrackerTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Try to unlock a password-protected PDF by testing a list of candidate passwords. "
            + "Use when the user says 'I forgot my PDF password', 'try these passwords on this PDF', "
            + "'unlock my encrypted PDF'. USER MUST CONFIRM the PDF belongs to them. "
            + "Candidates come from any combination of: a file with one password per line, "
            + "common-passwords seed (top 200 leaked), and user hints. "
            + "On success, saves a decrypted copy.")
    public String unlockPdfWithWordlist(
            @ToolParam(description = "Absolute path to the encrypted PDF") String pdfPath,
            @ToolParam(description = "Optional: absolute path to a wordlist file (one password per line). Empty string to skip.") String wordlistPath,
            @ToolParam(description = "Optional hints: pipe-separated extra candidates (e.g. 'birthday1990|cat|coffee'). Empty string to skip.") String hintsPipe,
            @ToolParam(description = "Include the top-200 most common leaked passwords in the attempt? true/false") boolean includeCommonPasswords,
            @ToolParam(description = "Absolute path for the unlocked output PDF (only written on success)") String outputPath,
            @ToolParam(description = "Ownership attestation — must be 'yes' to proceed") String iOwnThisPdf) {
        if (!"yes".equalsIgnoreCase(iOwnThisPdf != null ? iOwnThisPdf.trim() : "")) {
            return "Refused: please attest ownership by passing iOwnThisPdf='yes'. This tool is for recovering your own PDFs.";
        }
        File pdf = new File(pdfPath == null ? "" : pdfPath.trim());
        if (!pdf.isFile()) return "PDF not found: " + pdfPath;
        if (outputPath == null || outputPath.isBlank()) return "Provide an output path for the unlocked copy.";

        List<String> candidates = buildCandidates(wordlistPath, hintsPipe, includeCommonPasswords);
        if (candidates.isEmpty()) return "No candidate passwords to try.";
        if (candidates.size() > DEFAULT_MAX_ATTEMPTS) {
            candidates = candidates.subList(0, DEFAULT_MAX_ATTEMPTS);
        }

        notifier.notify("Trying " + candidates.size() + " candidate passwords...");
        long start = System.currentTimeMillis();
        int tried = 0;
        for (String pw : candidates) {
            tried++;
            if (tried % 200 == 0) notifier.notify("Tried " + tried + "/" + candidates.size() + "...");
            if (tryPassword(pdf, pw, outputPath)) {
                long secs = (System.currentTimeMillis() - start) / 1000;
                return "✅ Unlocked in " + tried + " attempt(s) (" + secs + "s). "
                        + "Password: \"" + pw + "\"\nUnlocked copy saved to: " + outputPath;
            }
        }
        long secs = (System.currentTimeMillis() - start) / 1000;
        return "❌ None of the " + tried + " candidates worked (" + secs + "s). "
                + "Try a larger wordlist or more hints.";
    }

    @Tool(description = "Test a single password against an encrypted PDF without saving any output. "
            + "Use when the user wants to quickly verify 'is the password X?'.")
    public String testPdfPassword(
            @ToolParam(description = "Absolute path to the PDF") String pdfPath,
            @ToolParam(description = "Password to test") String password) {
        File pdf = new File(pdfPath == null ? "" : pdfPath.trim());
        if (!pdf.isFile()) return "PDF not found.";
        try (PDDocument doc = Loader.loadPDF(pdf, password, org.apache.pdfbox.io.IOUtils.createMemoryOnlyStreamCache())) {
            return "✅ Password correct. The PDF has " + doc.getNumberOfPages() + " pages.";
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            return "❌ Wrong password.";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ─── Internals ──────────────────────────────────────────────

    private boolean tryPassword(File pdf, String password, String outputPath) {
        try (PDDocument doc = Loader.loadPDF(pdf, password, org.apache.pdfbox.io.IOUtils.createMemoryOnlyStreamCache())) {
            // Success — remove encryption and save unlocked copy
            doc.setAllSecurityToBeRemoved(true);
            Path out = Path.of(outputPath.trim());
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            doc.save(out.toFile());
            return true;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            return false;
        } catch (Exception e) {
            log.debug("[PdfCrack] non-password error on '{}': {}", password, e.getMessage());
            return false;
        }
    }

    private List<String> buildCandidates(String wordlistPath, String hintsPipe, boolean includeCommon) {
        List<String> out = new ArrayList<>();
        // 1. wordlist file
        if (wordlistPath != null && !wordlistPath.isBlank()) {
            try {
                for (String line : Files.readAllLines(Path.of(wordlistPath.trim()))) {
                    String t = line.trim();
                    if (!t.isEmpty()) out.add(t);
                }
            } catch (Exception e) {
                log.warn("[PdfCrack] wordlist read failed: {}", e.getMessage());
            }
        }
        // 2. user hints
        if (hintsPipe != null && !hintsPipe.isBlank()) {
            for (String h : hintsPipe.split("\\|")) {
                String t = h.trim();
                if (!t.isEmpty()) out.add(t);
                // Also add some common mutations of hints: append digits, capitalize, add !
                if (!t.isEmpty()) {
                    out.add(t + "1"); out.add(t + "123"); out.add(t + "!");
                    out.add(Character.toUpperCase(t.charAt(0)) + (t.length() > 1 ? t.substring(1) : ""));
                }
            }
        }
        // 3. top-200 common passwords
        if (includeCommon) {
            for (String p : TOP_200_COMMON) out.add(p);
        }
        // De-dupe preserving order
        java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>(out);
        return new ArrayList<>(dedup);
    }

    /** Top ~200 leaked passwords (from SecLists / HIBP statistics). */
    private static final String[] TOP_200_COMMON = {
        "123456","password","12345678","qwerty","123456789","12345","1234","111111","1234567",
        "dragon","123123","baseball","abc123","football","monkey","letmein","696969","shadow",
        "master","666666","qwertyuiop","123321","mustang","1234567890","michael","654321","superman",
        "1qaz2wsx","7777777","121212","000000","qazwsx","123qwe","killer","trustno1","jordan",
        "jennifer","zxcvbnm","asdfgh","hunter","buster","soccer","harley","batman","andrew",
        "tigger","sunshine","iloveyou","2000","charlie","robert","thomas","hockey","ranger",
        "daniel","starwars","klaster","112233","george","computer","michelle","jessica","pepper",
        "1111","zxcvbn","555555","11111111","131313","freedom","777777","pass","maggie","159753",
        "aaaaaa","ginger","princess","joshua","cheese","amanda","summer","love","ashley","6969",
        "nicole","chelsea","biteme","matthew","access","yankees","987654321","dallas","austin",
        "thunder","taylor","matrix","mobilemail","mom","monitor","monitoring","montana","moon",
        "moscow","bonjour","hello","welcome","admin","administrator","root","toor","changeme",
        "abcdef","abcd1234","a1b2c3","letmein1","P@ssw0rd","Passw0rd","passw0rd","P@55w0rd",
        "Qwerty123","Password1","password1","welcome1","admin123","admin1234","123abc","qwe123",
        "asdf1234","qwerty1","qwerty123","samsung","google","test","test123","demo","guest",
        "user","user1","default","master123","login","qwert","iloveyou1","loveyou","lovelove",
        "whatever","asshole","fuckyou","fuckme","777","2020","2021","2022","2023","2024","2025",
        "2026","01012000","07071990","qwerty!","1q2w3e","1q2w3e4r","zaq1zaq1","Asdf1234","AaAa1111",
        "trinity","system","public","private","secret","temporary","temp","temp123","Monday",
        "friday","january","qwe","azerty","francais","soleil","motdepasse","bonsoir"
    };
}
