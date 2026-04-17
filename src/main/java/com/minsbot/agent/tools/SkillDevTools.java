package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.cronvalidator.CronValidatorConfig;
import com.minsbot.skills.cronvalidator.CronValidatorService;
import com.minsbot.skills.encoder.EncoderConfig;
import com.minsbot.skills.encoder.EncoderService;
import com.minsbot.skills.hashcalc.HashCalcConfig;
import com.minsbot.skills.hashcalc.HashCalcService;
import com.minsbot.skills.hashidentifier.HashIdentifierConfig;
import com.minsbot.skills.hashidentifier.HashIdentifierService;
import com.minsbot.skills.jsontools.JsonToolsConfig;
import com.minsbot.skills.jsontools.JsonToolsService;
import com.minsbot.skills.randomgen.RandomGenConfig;
import com.minsbot.skills.randomgen.RandomGenService;
import com.minsbot.skills.regextester.RegexTesterConfig;
import com.minsbot.skills.regextester.RegexTesterService;
import com.minsbot.skills.unitconvert.UnitConvertConfig;
import com.minsbot.skills.unitconvert.UnitConvertService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Developer skills exposed as LLM tools: encoding, hashing, JSON, regex, random,
 * hash identification, cron parsing, unit conversion.
 */
@Component
public class SkillDevTools {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolExecutionNotifier notifier;

    private final EncoderService encoder; private final EncoderConfig.EncoderProperties encoderProps;
    private final HashCalcService hashCalc; private final HashCalcConfig.HashCalcProperties hashCalcProps;
    private final JsonToolsService jsonTools; private final JsonToolsConfig.JsonToolsProperties jsonProps;
    private final RegexTesterService regex; private final RegexTesterConfig.RegexTesterProperties regexProps;
    private final RandomGenService randomGen; private final RandomGenConfig.RandomGenProperties randomProps;
    private final HashIdentifierService hashId; private final HashIdentifierConfig.HashIdentifierProperties hashIdProps;
    private final CronValidatorService cron; private final CronValidatorConfig.CronValidatorProperties cronProps;
    private final UnitConvertService units; private final UnitConvertConfig.UnitConvertProperties unitsProps;

    public SkillDevTools(ToolExecutionNotifier notifier,
                         EncoderService encoder, EncoderConfig.EncoderProperties encoderProps,
                         HashCalcService hashCalc, HashCalcConfig.HashCalcProperties hashCalcProps,
                         JsonToolsService jsonTools, JsonToolsConfig.JsonToolsProperties jsonProps,
                         RegexTesterService regex, RegexTesterConfig.RegexTesterProperties regexProps,
                         RandomGenService randomGen, RandomGenConfig.RandomGenProperties randomProps,
                         HashIdentifierService hashId, HashIdentifierConfig.HashIdentifierProperties hashIdProps,
                         CronValidatorService cron, CronValidatorConfig.CronValidatorProperties cronProps,
                         UnitConvertService units, UnitConvertConfig.UnitConvertProperties unitsProps) {
        this.notifier = notifier;
        this.encoder = encoder; this.encoderProps = encoderProps;
        this.hashCalc = hashCalc; this.hashCalcProps = hashCalcProps;
        this.jsonTools = jsonTools; this.jsonProps = jsonProps;
        this.regex = regex; this.regexProps = regexProps;
        this.randomGen = randomGen; this.randomProps = randomProps;
        this.hashId = hashId; this.hashIdProps = hashIdProps;
        this.cron = cron; this.cronProps = cronProps;
        this.units = units; this.unitsProps = unitsProps;
    }

    @Tool(description = "Encode or decode text. Format can be: base64, base64url, hex, url. Operation: 'encode' or 'decode'.")
    public String encode(
            @ToolParam(description = "Text to encode or decode") String text,
            @ToolParam(description = "Format: base64, base64url, hex, or url") String format,
            @ToolParam(description = "Operation: 'encode' or 'decode'") String operation) {
        if (!encoderProps.isEnabled()) return disabled("encoder");
        notifier.notify("Encoder: " + operation + " " + format);
        try {
            String fmt = format.toLowerCase();
            String result = switch (operation.toLowerCase()) {
                case "encode" -> switch (fmt) {
                    case "base64" -> encoder.base64Encode(text);
                    case "base64url" -> encoder.base64UrlEncode(text);
                    case "hex" -> encoder.hexEncode(text);
                    case "url" -> encoder.urlEncode(text);
                    default -> throw new IllegalArgumentException("Unknown format: " + format);
                };
                case "decode" -> switch (fmt) {
                    case "base64" -> encoder.base64Decode(text);
                    case "base64url" -> encoder.base64UrlDecode(text);
                    case "hex" -> encoder.hexDecode(text);
                    case "url" -> encoder.urlDecode(text);
                    default -> throw new IllegalArgumentException("Unknown format: " + format);
                };
                default -> throw new IllegalArgumentException("Operation must be 'encode' or 'decode'");
            };
            return result;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Compute MD5, SHA-1, SHA-256, SHA-512 hashes of a string. Use when the user asks to hash text.")
    public String hashString(
            @ToolParam(description = "Text to hash") String input) {
        if (!hashCalcProps.isEnabled()) return disabled("hashcalc");
        notifier.notify("Hashing string...");
        try {
            Map<String, String> hashes = hashCalc.hashString(input, null);
            return toJson(hashes);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Validate, pretty-print, minify, or describe a JSON string. Operation: 'validate', 'pretty', 'minify', or 'describe'.")
    public String json(
            @ToolParam(description = "JSON text") String input,
            @ToolParam(description = "Operation: validate | pretty | minify | describe") String operation) {
        if (!jsonProps.isEnabled()) return disabled("jsontools");
        notifier.notify("JSON: " + operation);
        try {
            return switch (operation.toLowerCase()) {
                case "validate" -> toJson(jsonTools.validate(input));
                case "pretty" -> jsonTools.prettyPrint(input);
                case "minify" -> jsonTools.minify(input);
                case "describe" -> toJson(jsonTools.describe(input));
                default -> "Unknown operation: " + operation;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Test a regex pattern against input text. Returns matches with positions.")
    public String regexTest(
            @ToolParam(description = "Regex pattern") String pattern,
            @ToolParam(description = "Input text to search") String input,
            @ToolParam(description = "Optional flags: imsxu (case-insensitive, multiline, dotall, etc.) — empty string if none") String flags) {
        if (!regexProps.isEnabled()) return disabled("regextester");
        notifier.notify("Testing regex...");
        try {
            return toJson(regex.test(pattern, flags, input, regexProps.getMaxMatches()));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Generate random UUIDs, passwords, integers, or dice rolls. Kind: 'uuid', 'password', 'int', or 'dice'.")
    public String random(
            @ToolParam(description = "Kind: uuid | password | int | dice") String kind,
            @ToolParam(description = "Count (default 1)") double count,
            @ToolParam(description = "For password: length, for int: max, for dice: sides (default 6)") double optionalParam) {
        if (!randomProps.isEnabled()) return disabled("randomgen");
        int n = Math.max(1, (int) count);
        try {
            return switch (kind.toLowerCase()) {
                case "uuid" -> toJson(randomGen.uuids(n));
                case "password" -> toJson(randomGen.passwords(n, Math.max(8, (int) optionalParam), true, true, true));
                case "int" -> toJson(randomGen.integers(n, 0, Math.max(1, (long) optionalParam)));
                case "dice" -> toJson(randomGen.dice(n, Math.max(2, (int) optionalParam)));
                default -> "Unknown kind: " + kind;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Identify what kind of hash a string is (MD5, SHA-256, bcrypt, Argon2, JWT, etc.).")
    public String identifyHash(
            @ToolParam(description = "The hash string") String input) {
        if (!hashIdProps.isEnabled()) return disabled("hashidentifier");
        try { return toJson(hashId.identify(input)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Parse a cron expression and show the next N runs. Uses Spring 6-field format: second minute hour day-of-month month day-of-week.")
    public String cronNextRuns(
            @ToolParam(description = "Cron expression (e.g. '0 0 9 * * MON-FRI')") String cronExpression,
            @ToolParam(description = "How many next runs to show (default 5)") double count) {
        if (!cronProps.isEnabled()) return disabled("cronvalidator");
        int n = Math.max(1, Math.min(cronProps.getMaxNextRuns(), (int) count));
        try { return toJson(cron.nextRuns(cronExpression, n, null)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Convert between units. Categories: length, weight, time, data, temperature. "
            + "Examples: length m to ft, weight kg to lb, temperature C to F.")
    public String unitConvert(
            @ToolParam(description = "Category: length | weight | time | data | temperature") String category,
            @ToolParam(description = "Value to convert") double value,
            @ToolParam(description = "From unit (e.g. 'm', 'kg', 'C', 'MB')") String from,
            @ToolParam(description = "To unit") String to) {
        if (!unitsProps.isEnabled()) return disabled("unitconvert");
        try {
            double result = units.convert(category, value, from, to);
            return String.format("%s %s = %s %s", value, from, result, to);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); }
    }
}
