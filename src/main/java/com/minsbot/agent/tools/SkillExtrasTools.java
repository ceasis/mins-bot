package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.encryptionaes.EncryptionAesConfig;
import com.minsbot.skills.encryptionaes.EncryptionAesService;
import com.minsbot.skills.exifstripper.ExifStripperConfig;
import com.minsbot.skills.exifstripper.ExifStripperService;
import com.minsbot.skills.flashcardmaker.FlashcardMakerConfig;
import com.minsbot.skills.flashcardmaker.FlashcardMakerService;
import com.minsbot.skills.headlineanalyzer.HeadlineAnalyzerConfig;
import com.minsbot.skills.headlineanalyzer.HeadlineAnalyzerService;
import com.minsbot.skills.markdownhtml.MarkdownHtmlConfig;
import com.minsbot.skills.markdownhtml.MarkdownHtmlService;
import com.minsbot.skills.numberwords.NumberWordsConfig;
import com.minsbot.skills.numberwords.NumberWordsService;
import com.minsbot.skills.piiredactor.PiiRedactorConfig;
import com.minsbot.skills.piiredactor.PiiRedactorService;
import com.minsbot.skills.pomodoroplanner.PomodoroPlannerConfig;
import com.minsbot.skills.pomodoroplanner.PomodoroPlannerService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SkillExtrasTools {

    private final ObjectMapper mapper = new ObjectMapper();

    private final MarkdownHtmlService mdHtml; private final MarkdownHtmlConfig.MarkdownHtmlProperties mdHtmlProps;
    private final HeadlineAnalyzerService headline; private final HeadlineAnalyzerConfig.HeadlineAnalyzerProperties headlineProps;
    private final NumberWordsService numWords; private final NumberWordsConfig.NumberWordsProperties numProps;
    private final PiiRedactorService pii; private final PiiRedactorConfig.PiiRedactorProperties piiProps;
    private final ExifStripperService exif; private final ExifStripperConfig.ExifStripperProperties exifProps;
    private final EncryptionAesService aes; private final EncryptionAesConfig.EncryptionAesProperties aesProps;
    private final FlashcardMakerService flash; private final FlashcardMakerConfig.FlashcardMakerProperties flashProps;
    private final PomodoroPlannerService pomo; private final PomodoroPlannerConfig.PomodoroPlannerProperties pomoProps;

    public SkillExtrasTools(MarkdownHtmlService mdHtml, MarkdownHtmlConfig.MarkdownHtmlProperties mdHtmlProps,
                            HeadlineAnalyzerService headline, HeadlineAnalyzerConfig.HeadlineAnalyzerProperties headlineProps,
                            NumberWordsService numWords, NumberWordsConfig.NumberWordsProperties numProps,
                            PiiRedactorService pii, PiiRedactorConfig.PiiRedactorProperties piiProps,
                            ExifStripperService exif, ExifStripperConfig.ExifStripperProperties exifProps,
                            EncryptionAesService aes, EncryptionAesConfig.EncryptionAesProperties aesProps,
                            FlashcardMakerService flash, FlashcardMakerConfig.FlashcardMakerProperties flashProps,
                            PomodoroPlannerService pomo, PomodoroPlannerConfig.PomodoroPlannerProperties pomoProps) {
        this.mdHtml = mdHtml; this.mdHtmlProps = mdHtmlProps;
        this.headline = headline; this.headlineProps = headlineProps;
        this.numWords = numWords; this.numProps = numProps;
        this.pii = pii; this.piiProps = piiProps;
        this.exif = exif; this.exifProps = exifProps;
        this.aes = aes; this.aesProps = aesProps;
        this.flash = flash; this.flashProps = flashProps;
        this.pomo = pomo; this.pomoProps = pomoProps;
    }

    @Tool(description = "Convert Markdown to HTML or HTML to Markdown. Direction: 'to-html' or 'to-markdown'.")
    public String markdownHtmlConvert(@ToolParam(description = "Input text") String input,
                                      @ToolParam(description = "Direction: to-html | to-markdown") String direction) {
        if (!mdHtmlProps.isEnabled()) return disabled("markdownhtml");
        return switch (direction.toLowerCase()) {
            case "to-html" -> mdHtml.mdToHtml(input);
            case "to-markdown" -> mdHtml.htmlToMd(input);
            default -> "Unknown direction: " + direction;
        };
    }

    @Tool(description = "Score a headline for effectiveness (power/emotional words, length, presence of numbers). Returns 0-100 score and notes.")
    public String analyzeHeadline(@ToolParam(description = "Headline text") String headline) {
        if (!headlineProps.isEnabled()) return disabled("headlineanalyzer");
        return toJson(this.headline.analyze(headline));
    }

    @Tool(description = "Convert numbers to/from words or Roman numerals. Operation: 'to-words' | 'from-words' | 'to-roman' | 'from-roman'.")
    public String numberWords(@ToolParam(description = "Operation") String operation,
                              @ToolParam(description = "Input (number or text)") String input) {
        if (!numProps.isEnabled()) return disabled("numberwords");
        try {
            return switch (operation.toLowerCase()) {
                case "to-words" -> numWords.toWords(Long.parseLong(input.trim()));
                case "from-words" -> String.valueOf(numWords.fromWords(input));
                case "to-roman" -> numWords.toRoman(Integer.parseInt(input.trim()));
                case "from-roman" -> String.valueOf(numWords.fromRoman(input));
                default -> "Unknown operation: " + operation;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Redact PII from text (emails, phone numbers, SSNs, credit cards, IP addresses, etc.). Returns redacted text and per-type counts.")
    public String redactPii(@ToolParam(description = "Text to redact") String text) {
        if (!piiProps.isEnabled()) return disabled("piiredactor");
        return toJson(pii.redact(text, null));
    }

    @Tool(description = "Strip EXIF/IPTC/XMP metadata from an image file. Writes a clean copy; returns input/output paths.")
    public String stripExif(@ToolParam(description = "Input image path (absolute)") String inputPath,
                            @ToolParam(description = "Output path (empty for auto-derived path)") String outputPath) {
        if (!exifProps.isEnabled()) return disabled("exifstripper");
        try { return toJson(exif.strip(inputPath, outputPath == null || outputPath.isBlank() ? null : outputPath, exifProps.getMaxFileBytes())); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "AES-256-GCM text encryption/decryption with PBKDF2 key derivation. Operation: 'encrypt' | 'decrypt'.")
    public String encryptAes(@ToolParam(description = "Operation: encrypt|decrypt") String operation,
                             @ToolParam(description = "For encrypt: plaintext. For decrypt: base64 ciphertext.") String input,
                             @ToolParam(description = "Passphrase") String passphrase) {
        if (!aesProps.isEnabled()) return disabled("encryptionaes");
        try {
            return switch (operation.toLowerCase()) {
                case "encrypt" -> toJson(aes.encryptText(input, passphrase));
                case "decrypt" -> toJson(aes.decryptText(input, passphrase));
                default -> "Unknown operation: " + operation;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Generate flashcards from delimited text (Q::A format, one per line). Returns Anki-ready CSV plus parsed cards.")
    public String makeFlashcards(@ToolParam(description = "Text with 'Question::Answer' pairs, one per line") String text,
                                 @ToolParam(description = "Separator between Q and A (default '::')") String separator) {
        if (!flashProps.isEnabled()) return disabled("flashcardmaker");
        try { return toJson(flash.fromDelimitedText(text, separator)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Generate a Pomodoro schedule from a task list. Pass tasks as JSON array of {name, pomodoros}.")
    public String pomodoroSchedule(@ToolParam(description = "Tasks JSON: [{\"name\":\"Task A\",\"pomodoros\":2},...]") String tasksJson,
                                   @ToolParam(description = "Start time 'HH:mm' (empty for 09:00)") String startTime,
                                   @ToolParam(description = "Work minutes per pomodoro (default 25)") double workMinutes) {
        if (!pomoProps.isEnabled()) return disabled("pomodoroplanner");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = mapper.readValue(tasksJson, List.class);
            int w = workMinutes <= 0 ? 25 : (int) workMinutes;
            return toJson(pomo.plan(tasks, startTime, w, 5, 15, 4));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }
    private String toJson(Object obj) { try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); } }
}
