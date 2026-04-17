package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.breakevencalc.BreakEvenCalcConfig;
import com.minsbot.skills.breakevencalc.BreakEvenCalcService;
import com.minsbot.skills.cashflowforecast.CashflowForecastConfig;
import com.minsbot.skills.cashflowforecast.CashflowForecastService;
import com.minsbot.skills.depreciationcalc.DepreciationCalcConfig;
import com.minsbot.skills.depreciationcalc.DepreciationCalcService;
import com.minsbot.skills.geodistance.GeoDistanceConfig;
import com.minsbot.skills.geodistance.GeoDistanceService;
import com.minsbot.skills.heartratezones.HeartRateZonesConfig;
import com.minsbot.skills.heartratezones.HeartRateZonesService;
import com.minsbot.skills.macrocalc.MacroCalcConfig;
import com.minsbot.skills.macrocalc.MacroCalcService;
import com.minsbot.skills.matrixops.MatrixOpsConfig;
import com.minsbot.skills.matrixops.MatrixOpsService;
import com.minsbot.skills.pacecalc.PaceCalcConfig;
import com.minsbot.skills.pacecalc.PaceCalcService;
import com.minsbot.skills.physicscalc.PhysicsCalcConfig;
import com.minsbot.skills.physicscalc.PhysicsCalcService;
import com.minsbot.skills.probabilitycalc.ProbabilityCalcConfig;
import com.minsbot.skills.probabilitycalc.ProbabilityCalcService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SkillCalcTools {

    private final ObjectMapper mapper = new ObjectMapper();

    private final ProbabilityCalcService prob; private final ProbabilityCalcConfig.ProbabilityCalcProperties probProps;
    private final MatrixOpsService matrix; private final MatrixOpsConfig.MatrixOpsProperties matrixProps;
    private final PhysicsCalcService physics; private final PhysicsCalcConfig.PhysicsCalcProperties physProps;
    private final GeoDistanceService geo; private final GeoDistanceConfig.GeoDistanceProperties geoProps;
    private final BreakEvenCalcService be; private final BreakEvenCalcConfig.BreakEvenCalcProperties beProps;
    private final DepreciationCalcService dep; private final DepreciationCalcConfig.DepreciationCalcProperties depProps;
    private final CashflowForecastService cf; private final CashflowForecastConfig.CashflowForecastProperties cfProps;
    private final MacroCalcService macro; private final MacroCalcConfig.MacroCalcProperties macroProps;
    private final PaceCalcService pace; private final PaceCalcConfig.PaceCalcProperties paceProps;
    private final HeartRateZonesService hr; private final HeartRateZonesConfig.HeartRateZonesProperties hrProps;

    public SkillCalcTools(ProbabilityCalcService prob, ProbabilityCalcConfig.ProbabilityCalcProperties probProps,
                          MatrixOpsService matrix, MatrixOpsConfig.MatrixOpsProperties matrixProps,
                          PhysicsCalcService physics, PhysicsCalcConfig.PhysicsCalcProperties physProps,
                          GeoDistanceService geo, GeoDistanceConfig.GeoDistanceProperties geoProps,
                          BreakEvenCalcService be, BreakEvenCalcConfig.BreakEvenCalcProperties beProps,
                          DepreciationCalcService dep, DepreciationCalcConfig.DepreciationCalcProperties depProps,
                          CashflowForecastService cf, CashflowForecastConfig.CashflowForecastProperties cfProps,
                          MacroCalcService macro, MacroCalcConfig.MacroCalcProperties macroProps,
                          PaceCalcService pace, PaceCalcConfig.PaceCalcProperties paceProps,
                          HeartRateZonesService hr, HeartRateZonesConfig.HeartRateZonesProperties hrProps) {
        this.prob = prob; this.probProps = probProps;
        this.matrix = matrix; this.matrixProps = matrixProps;
        this.physics = physics; this.physProps = physProps;
        this.geo = geo; this.geoProps = geoProps;
        this.be = be; this.beProps = beProps;
        this.dep = dep; this.depProps = depProps;
        this.cf = cf; this.cfProps = cfProps;
        this.macro = macro; this.macroProps = macroProps;
        this.pace = pace; this.paceProps = paceProps;
        this.hr = hr; this.hrProps = hrProps;
    }

    @Tool(description = "Probability calculations. Kind: 'factorial' (1 arg n), 'combinations' (n,k), 'permutations' (n,k), 'binomial' (n,k,p where p is 0-1), 'normal' (x,mean,stdev), 'poisson' (k,lambda).")
    public String probability(@ToolParam(description = "Kind: factorial|combinations|permutations|binomial|normal|poisson") String kind,
                              @ToolParam(description = "Arg 1 (n, x, or k)") double a,
                              @ToolParam(description = "Arg 2 (k, mean, or lambda; 0 if unused)") double b,
                              @ToolParam(description = "Arg 3 (p or stdev; 0 if unused)") double c) {
        if (!probProps.isEnabled()) return disabled("probabilitycalc");
        try {
            return switch (kind.toLowerCase()) {
                case "factorial" -> toJson(prob.factorial((long) a));
                case "combinations" -> toJson(prob.combinations((long) a, (long) b));
                case "permutations" -> toJson(prob.permutations((long) a, (long) b));
                case "binomial" -> toJson(prob.binomial((int) a, (int) b, c));
                case "normal" -> toJson(prob.normal(a, b, c));
                case "poisson" -> toJson(prob.poisson((int) a, b));
                default -> "Unknown kind: " + kind;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Matrix operation. Operation: 'add' | 'multiply' | 'transpose' | 'determinant'. Pass matrix A (and B if binary op) as JSON arrays of arrays of numbers.")
    public String matrixOp(@ToolParam(description = "Operation: add|multiply|transpose|determinant") String operation,
                           @ToolParam(description = "Matrix A as JSON (e.g. [[1,2],[3,4]])") String aJson,
                           @ToolParam(description = "Matrix B as JSON (or empty for unary ops)") String bJson) {
        if (!matrixProps.isEnabled()) return disabled("matrixops");
        try {
            @SuppressWarnings("unchecked")
            List<List<Number>> aList = mapper.readValue(aJson, List.class);
            double[][] a = matrix.fromList(aList);
            return switch (operation.toLowerCase()) {
                case "add" -> { @SuppressWarnings("unchecked") List<List<Number>> bList = mapper.readValue(bJson, List.class); yield toJson(matrix.add(a, matrix.fromList(bList))); }
                case "multiply" -> { @SuppressWarnings("unchecked") List<List<Number>> bList = mapper.readValue(bJson, List.class); yield toJson(matrix.multiply(a, matrix.fromList(bList))); }
                case "transpose" -> toJson(matrix.transpose(a));
                case "determinant" -> toJson(Map.of("determinant", matrix.determinant(a)));
                default -> "Unknown operation: " + operation;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Physics equation. Kind: 'velocity' (u,a,t), 'displacement' (u,a,t), 'kinetic' (mass,v,_), 'potential' (mass,h,g), 'force' (mass,a,_), 'power' (work,t,_), 'pressure' (F,A,_).")
    public String physicsEquation(@ToolParam(description = "Kind") String kind,
                                  @ToolParam(description = "Arg 1") double a,
                                  @ToolParam(description = "Arg 2") double b,
                                  @ToolParam(description = "Arg 3 (use 0 if unused)") double c) {
        if (!physProps.isEnabled()) return disabled("physicscalc");
        try {
            return switch (kind.toLowerCase()) {
                case "velocity" -> toJson(physics.kinematicVelocity(a, b, c));
                case "displacement" -> toJson(physics.kinematicDisplacement(a, b, c));
                case "kinetic" -> toJson(physics.kineticEnergy(a, b));
                case "potential" -> toJson(physics.potentialEnergy(a, b, c == 0 ? 9.80665 : c));
                case "force" -> toJson(physics.force(a, b));
                case "power" -> toJson(physics.power(a, b));
                case "pressure" -> toJson(physics.pressure(a, b));
                default -> "Unknown kind: " + kind;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Great-circle (haversine) distance between two lat/lon points in km, mi, and nautical miles.")
    public String geoDistance(@ToolParam(description = "Latitude 1") double lat1,
                              @ToolParam(description = "Longitude 1") double lon1,
                              @ToolParam(description = "Latitude 2") double lat2,
                              @ToolParam(description = "Longitude 2") double lon2) {
        if (!geoProps.isEnabled()) return disabled("geodistance");
        return toJson(geo.haversine(lat1, lon1, lat2, lon2));
    }

    @Tool(description = "Break-even analysis: units and revenue required to cover fixed costs.")
    public String breakEven(@ToolParam(description = "Fixed costs") double fixedCosts,
                            @ToolParam(description = "Price per unit") double pricePerUnit,
                            @ToolParam(description = "Variable cost per unit") double variableCostPerUnit) {
        if (!beProps.isEnabled()) return disabled("breakevencalc");
        try { return toJson(be.compute(fixedCosts, pricePerUnit, variableCostPerUnit)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Depreciation schedule. Method: 'straight-line' | 'declining-balance' | 'sum-of-years'.")
    public String depreciation(@ToolParam(description = "Method") String method,
                               @ToolParam(description = "Cost") double cost,
                               @ToolParam(description = "Salvage value") double salvage,
                               @ToolParam(description = "Useful life in years") double usefulYears) {
        if (!depProps.isEnabled()) return disabled("depreciationcalc");
        int years = (int) usefulYears;
        try {
            return switch (method.toLowerCase()) {
                case "straight-line" -> toJson(dep.straightLine(cost, salvage, years));
                case "declining-balance" -> toJson(dep.decliningBalance(cost, salvage, years, 2.0));
                case "sum-of-years" -> toJson(dep.sumOfYearsDigits(cost, salvage, years));
                default -> "Unknown method: " + method;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Project monthly cashflow over N months with growth rates.")
    public String cashflowProject(@ToolParam(description = "Opening balance") double openingBalance,
                                  @ToolParam(description = "Monthly inflow") double monthlyInflow,
                                  @ToolParam(description = "Monthly outflow") double monthlyOutflow,
                                  @ToolParam(description = "Inflow month-over-month growth % (e.g. 2 for 2%)") double inflowGrowthPct,
                                  @ToolParam(description = "Outflow month-over-month growth %") double outflowGrowthPct,
                                  @ToolParam(description = "Number of months (default 12)") double months) {
        if (!cfProps.isEnabled()) return disabled("cashflowforecast");
        int m = months <= 0 ? 12 : (int) months;
        try { return toJson(cf.project(openingBalance, monthlyInflow, monthlyOutflow, inflowGrowthPct, outflowGrowthPct, m)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Macronutrient split from daily calories. Goal: 'balanced' | 'keto' | 'low-carb' | 'cut' | 'bulk' | 'endurance'.")
    public String macroSplit(@ToolParam(description = "Daily calories (kcal)") double calories,
                             @ToolParam(description = "Goal preset") String goal) {
        if (!macroProps.isEnabled()) return disabled("macrocalc");
        return toJson(macro.computeFromCalories(calories, goal));
    }

    @Tool(description = "Running pace / time / distance calculator. Kind: 'from-time' (distanceKm, minutes total), 'from-pace' (distanceKm, paceMin per km).")
    public String runningPace(@ToolParam(description = "Kind: from-time | from-pace") String kind,
                              @ToolParam(description = "Distance in km") double distanceKm,
                              @ToolParam(description = "Minutes (total time or pace-per-km minutes)") double minutes,
                              @ToolParam(description = "Seconds (total time or pace-per-km seconds)") double seconds) {
        if (!paceProps.isEnabled()) return disabled("pacecalc");
        try {
            return switch (kind.toLowerCase()) {
                case "from-time" -> toJson(pace.fromTime(distanceKm, 0, (int) minutes, (int) seconds));
                case "from-pace" -> toJson(pace.fromPace(distanceKm, (int) minutes, (int) seconds));
                default -> "Unknown kind: " + kind;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Heart rate training zones from age (and optional resting HR for Karvonen method).")
    public String heartRateZones(@ToolParam(description = "Age in years") double age,
                                 @ToolParam(description = "Resting heart rate in bpm (optional, pass 0 to skip)") double restingHr) {
        if (!hrProps.isEnabled()) return disabled("heartratezones");
        try { return toJson(hr.zones((int) age, restingHr > 0 ? (int) restingHr : null)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }
    private String toJson(Object obj) { try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); } }
}
