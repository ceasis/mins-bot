package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.bmicalc.BmiCalcConfig;
import com.minsbot.skills.bmicalc.BmiCalcService;
import com.minsbot.skills.citationformatter.CitationFormatterConfig;
import com.minsbot.skills.citationformatter.CitationFormatterService;
import com.minsbot.skills.colortools.ColorToolsConfig;
import com.minsbot.skills.colortools.ColorToolsService;
import com.minsbot.skills.financecalc.FinanceCalcConfig;
import com.minsbot.skills.financecalc.FinanceCalcService;
import com.minsbot.skills.geometrycalc.GeometryCalcConfig;
import com.minsbot.skills.geometrycalc.GeometryCalcService;
import com.minsbot.skills.gradecalc.GradeCalcConfig;
import com.minsbot.skills.gradecalc.GradeCalcService;
import com.minsbot.skills.imagemeta.ImageMetaConfig;
import com.minsbot.skills.imagemeta.ImageMetaService;
import com.minsbot.skills.langdetector.LangDetectorConfig;
import com.minsbot.skills.langdetector.LangDetectorService;
import com.minsbot.skills.medicalunits.MedicalUnitsConfig;
import com.minsbot.skills.medicalunits.MedicalUnitsService;
import com.minsbot.skills.realestatecalc.RealEstateCalcConfig;
import com.minsbot.skills.realestatecalc.RealEstateCalcService;
import com.minsbot.skills.recipescaler.RecipeScalerConfig;
import com.minsbot.skills.recipescaler.RecipeScalerService;
import com.minsbot.skills.statsbasics.StatsBasicsConfig;
import com.minsbot.skills.statsbasics.StatsBasicsService;
import com.minsbot.skills.stockindicators.StockIndicatorsConfig;
import com.minsbot.skills.stockindicators.StockIndicatorsService;
import com.minsbot.skills.taxcalc.TaxCalcConfig;
import com.minsbot.skills.taxcalc.TaxCalcService;
import com.minsbot.skills.writingtools.WritingToolsConfig;
import com.minsbot.skills.writingtools.WritingToolsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SkillProfessionTools {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolExecutionNotifier notifier;

    private final FinanceCalcService finance; private final FinanceCalcConfig.FinanceCalcProperties finProps;
    private final TaxCalcService tax; private final TaxCalcConfig.TaxCalcProperties taxProps;
    private final RealEstateCalcService realEstate; private final RealEstateCalcConfig.RealEstateCalcProperties reProps;
    private final StockIndicatorsService stock; private final StockIndicatorsConfig.StockIndicatorsProperties stockProps;
    private final BmiCalcService bmi; private final BmiCalcConfig.BmiCalcProperties bmiProps;
    private final MedicalUnitsService medUnits; private final MedicalUnitsConfig.MedicalUnitsProperties medProps;
    private final RecipeScalerService recipe; private final RecipeScalerConfig.RecipeScalerProperties recipeProps;
    private final GradeCalcService grade; private final GradeCalcConfig.GradeCalcProperties gradeProps;
    private final GeometryCalcService geo; private final GeometryCalcConfig.GeometryCalcProperties geoProps;
    private final CitationFormatterService cite; private final CitationFormatterConfig.CitationFormatterProperties citeProps;
    private final StatsBasicsService stats; private final StatsBasicsConfig.StatsBasicsProperties statsProps;
    private final WritingToolsService writing; private final WritingToolsConfig.WritingToolsProperties writeProps;
    private final LangDetectorService lang; private final LangDetectorConfig.LangDetectorProperties langProps;
    private final ColorToolsService color; private final ColorToolsConfig.ColorToolsProperties colorProps;
    private final ImageMetaService imageMeta; private final ImageMetaConfig.ImageMetaProperties imgProps;

    public SkillProfessionTools(ToolExecutionNotifier notifier,
                                FinanceCalcService finance, FinanceCalcConfig.FinanceCalcProperties finProps,
                                TaxCalcService tax, TaxCalcConfig.TaxCalcProperties taxProps,
                                RealEstateCalcService realEstate, RealEstateCalcConfig.RealEstateCalcProperties reProps,
                                StockIndicatorsService stock, StockIndicatorsConfig.StockIndicatorsProperties stockProps,
                                BmiCalcService bmi, BmiCalcConfig.BmiCalcProperties bmiProps,
                                MedicalUnitsService medUnits, MedicalUnitsConfig.MedicalUnitsProperties medProps,
                                RecipeScalerService recipe, RecipeScalerConfig.RecipeScalerProperties recipeProps,
                                GradeCalcService grade, GradeCalcConfig.GradeCalcProperties gradeProps,
                                GeometryCalcService geo, GeometryCalcConfig.GeometryCalcProperties geoProps,
                                CitationFormatterService cite, CitationFormatterConfig.CitationFormatterProperties citeProps,
                                StatsBasicsService stats, StatsBasicsConfig.StatsBasicsProperties statsProps,
                                WritingToolsService writing, WritingToolsConfig.WritingToolsProperties writeProps,
                                LangDetectorService lang, LangDetectorConfig.LangDetectorProperties langProps,
                                ColorToolsService color, ColorToolsConfig.ColorToolsProperties colorProps,
                                ImageMetaService imageMeta, ImageMetaConfig.ImageMetaProperties imgProps) {
        this.notifier = notifier;
        this.finance = finance; this.finProps = finProps;
        this.tax = tax; this.taxProps = taxProps;
        this.realEstate = realEstate; this.reProps = reProps;
        this.stock = stock; this.stockProps = stockProps;
        this.bmi = bmi; this.bmiProps = bmiProps;
        this.medUnits = medUnits; this.medProps = medProps;
        this.recipe = recipe; this.recipeProps = recipeProps;
        this.grade = grade; this.gradeProps = gradeProps;
        this.geo = geo; this.geoProps = geoProps;
        this.cite = cite; this.citeProps = citeProps;
        this.stats = stats; this.statsProps = statsProps;
        this.writing = writing; this.writeProps = writeProps;
        this.lang = lang; this.langProps = langProps;
        this.color = color; this.colorProps = colorProps;
        this.imageMeta = imageMeta; this.imgProps = imgProps;
    }

    @Tool(description = "Calculate compound interest. Returns final amount and interest earned.")
    public String compoundInterest(
            @ToolParam(description = "Principal (initial amount)") double principal,
            @ToolParam(description = "Annual interest rate in %") double annualRatePct,
            @ToolParam(description = "Years") double years,
            @ToolParam(description = "Compounds per year (12=monthly, 365=daily, 1=annually)") double compoundsPerYear) {
        if (!finProps.isEnabled()) return disabled("financecalc");
        try { return toJson(finance.compound(principal, annualRatePct, (int) years, (int) compoundsPerYear)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Calculate monthly loan payment (amortizing). Returns P+I monthly, total interest, total paid.")
    public String loanPayment(
            @ToolParam(description = "Loan principal") double principal,
            @ToolParam(description = "Annual interest rate %") double annualRatePct,
            @ToolParam(description = "Loan term in years") double termYears) {
        if (!finProps.isEnabled()) return disabled("financecalc");
        try { return toJson(finance.loanPayment(principal, annualRatePct, (int) termYears)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Progressive tax calculator. brackets is a JSON array of {upperLimit, ratePct} objects. Pass null upperLimit for the top bracket.")
    public String calculateTax(
            @ToolParam(description = "Taxable income") double income,
            @ToolParam(description = "JSON array of brackets, e.g. [{\"upperLimit\":10000,\"ratePct\":10},{\"ratePct\":22}]") String bracketsJson) {
        if (!taxProps.isEnabled()) return disabled("taxcalc");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> brackets = mapper.readValue(bracketsJson, List.class);
            return toJson(tax.computeBrackets(income, brackets));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Mortgage PITI calculator (principal, interest, taxes, insurance, HOA).")
    public String mortgage(
            @ToolParam(description = "Property price") double price,
            @ToolParam(description = "Down payment") double downPayment,
            @ToolParam(description = "Annual rate %") double annualRatePct,
            @ToolParam(description = "Term in years (default 30)") double termYears) {
        if (!reProps.isEnabled()) return disabled("realestatecalc");
        try { return toJson(realEstate.mortgage(price, downPayment, annualRatePct, (int) termYears, null, null, null)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Calculate stock technical indicators (SMA, EMA, RSI) from a price array. Indicator: 'sma', 'ema', or 'rsi'.")
    public String stockIndicator(
            @ToolParam(description = "JSON array of closing prices") String pricesJson,
            @ToolParam(description = "Indicator: sma | ema | rsi") String indicator,
            @ToolParam(description = "Period (default 14)") double period) {
        if (!stockProps.isEnabled()) return disabled("stockindicators");
        try {
            @SuppressWarnings("unchecked")
            List<Number> nums = mapper.readValue(pricesJson, List.class);
            List<Double> prices = nums.stream().map(Number::doubleValue).toList();
            int p = Math.max(1, (int) period);
            return switch (indicator.toLowerCase()) {
                case "sma" -> toJson(stock.sma(prices, p));
                case "ema" -> toJson(stock.ema(prices, p));
                case "rsi" -> toJson(stock.rsi(prices, p));
                default -> "Unknown indicator: " + indicator;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Calculate BMI, BMR, or TDEE. What: 'bmi', 'bmr', or 'tdee'.")
    public String bodyMetric(
            @ToolParam(description = "Metric: bmi | bmr | tdee") String what,
            @ToolParam(description = "Weight in kg") double weightKg,
            @ToolParam(description = "Height in cm") double heightCm,
            @ToolParam(description = "Age in years (for bmr/tdee; 0 for bmi)") double age,
            @ToolParam(description = "Sex: 'male' or 'female' (for bmr/tdee; empty for bmi)") String sex) {
        if (!bmiProps.isEnabled()) return disabled("bmicalc");
        try {
            return switch (what.toLowerCase()) {
                case "bmi" -> toJson(bmi.bmi(weightKg, heightCm));
                case "bmr" -> toJson(bmi.bmr(weightKg, heightCm, (int) age, sex));
                case "tdee" -> toJson(bmi.tdee(weightKg, heightCm, (int) age, sex, "moderate"));
                default -> "Unknown metric: " + what;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Convert medical lab units (mg/dL <-> mmol/L) for analytes like glucose, cholesterol, creatinine, etc.")
    public String convertMedicalUnit(
            @ToolParam(description = "Analyte name (e.g. 'glucose', 'cholesterol', 'creatinine')") String analyte,
            @ToolParam(description = "Value to convert") double value,
            @ToolParam(description = "From unit: 'mg/dL' or 'mmol/L'") String fromUnit,
            @ToolParam(description = "To unit: 'mg/dL' or 'mmol/L'") String toUnit) {
        if (!medProps.isEnabled()) return disabled("medicalunits");
        try { return toJson(medUnits.convert(analyte, value, fromUnit, toUnit)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Scale recipe ingredients to a different serving count. Pass ingredients as JSON array of {name, amount, unit}.")
    public String scaleRecipe(
            @ToolParam(description = "JSON array of ingredients [{name, amount, unit}, ...]") String ingredientsJson,
            @ToolParam(description = "Original recipe servings") double originalServings,
            @ToolParam(description = "Target servings") double targetServings) {
        if (!recipeProps.isEnabled()) return disabled("recipescaler");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ings = mapper.readValue(ingredientsJson, List.class);
            return toJson(recipe.scale(ings, (int) originalServings, (int) targetServings));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Calculate a weighted grade from a list of items. items is JSON array of {name, score, weight}.")
    public String calculateGrade(@ToolParam(description = "JSON array of graded items [{name, score, weight}, ...]") String itemsJson) {
        if (!gradeProps.isEnabled()) return disabled("gradecalc");
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = mapper.readValue(itemsJson, List.class);
            return toJson(grade.weighted(items));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Compute area/perimeter of 2D shapes (square, rectangle, circle, triangle, trapezoid, ellipse, regular-polygon). dimensions is JSON map.")
    public String geometry2d(
            @ToolParam(description = "Shape: square | rectangle | circle | triangle | trapezoid | ellipse | regular-polygon") String shape,
            @ToolParam(description = "JSON map of dimensions, e.g. {\"radius\":5} or {\"width\":3,\"height\":4}") String dimensionsJson) {
        if (!geoProps.isEnabled()) return disabled("geometrycalc");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Double> dims = mapper.readValue(dimensionsJson, Map.class);
            return toJson(geo.shape2d(shape, dims));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Format a citation in APA, MLA, Chicago, Harvard, or IEEE style. Pass source as JSON map with authors, year, title, journal/publisher, etc.")
    public String formatCitation(
            @ToolParam(description = "Style: APA | MLA | CHICAGO | HARVARD | IEEE") String style,
            @ToolParam(description = "JSON source map {authors, year, title, journal, volume, issue, pages, publisher, city, url}") String sourceJson) {
        if (!citeProps.isEnabled()) return disabled("citationformatter");
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> source = mapper.readValue(sourceJson, Map.class);
            return toJson(cite.format(source, style));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Descriptive statistics on a numeric array: mean/median/stdev/quartiles/percentiles.")
    public String describeStats(@ToolParam(description = "JSON array of numbers") String valuesJson) {
        if (!statsProps.isEnabled()) return disabled("statsbasics");
        try {
            @SuppressWarnings("unchecked")
            List<Number> nums = mapper.readValue(valuesJson, List.class);
            return toJson(stats.describe(nums.stream().map(Number::doubleValue).toList()));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Analyze writing quality: word count, passive voice, adverb ratio, weasel words, reading time.")
    public String analyzeWriting(@ToolParam(description = "Text to analyze") String text) {
        if (!writeProps.isEnabled()) return disabled("writingtools");
        return toJson(writing.analyze(text, writeProps.getWordsPerMinute()));
    }

    @Tool(description = "Detect the language of a text (11 scripts + Latin-language heuristic).")
    public String detectLanguage(@ToolParam(description = "Text to detect language of") String text) {
        if (!langProps.isEnabled()) return disabled("langdetector");
        return toJson(lang.detect(text));
    }

    @Tool(description = "Color operations: 'parse' (hex/rgb to all formats), 'contrast' (WCAG ratio), 'palette' (complementary/triadic/analogous/tetradic/monochromatic).")
    public String colorTool(
            @ToolParam(description = "Operation: parse | contrast | palette") String operation,
            @ToolParam(description = "Primary color (hex/rgb)") String color1,
            @ToolParam(description = "For contrast: second color. For palette: scheme name. For parse: ignored.") String color2OrScheme) {
        if (!colorProps.isEnabled()) return disabled("colortools");
        try {
            return switch (operation.toLowerCase()) {
                case "parse" -> toJson(this.color.parse(color1));
                case "contrast" -> toJson(this.color.contrast(color1, color2OrScheme));
                case "palette" -> toJson(this.color.palette(color1, color2OrScheme));
                default -> "Unknown operation: " + operation;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Inspect an image file's dimensions, format, aspect ratio, and megapixels.")
    public String inspectImage(@ToolParam(description = "Absolute path to the image file") String path) {
        if (!imgProps.isEnabled()) return disabled("imagemeta");
        try { return toJson(imageMeta.inspect(path, imgProps.getMaxFileBytes())); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }
    private String toJson(Object obj) { try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); } }
}
