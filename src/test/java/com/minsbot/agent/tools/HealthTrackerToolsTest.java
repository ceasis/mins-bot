package com.minsbot.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class HealthTrackerToolsTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @TempDir
    Path tempDir;

    private HealthTrackerTools tools;

    @BeforeEach
    void setUp() throws Exception {
        ToolExecutionNotifier notifier = new ToolExecutionNotifier();
        tools = new HealthTrackerTools(notifier);

        // Redirect HEALTH_DIR to temp directory using Unsafe (static final in Java 17)
        TestReflectionUtil.setStaticField(HealthTrackerTools.class,
                "HEALTH_DIR", tempDir.resolve("health"));
    }

    @Test
    void testLogWater() {
        String result = tools.logWater(3);
        assertThat(result).contains("Logged 3 glass(es) of water");
        assertThat(result).contains("Total today: 3 glass(es)");

        // Log more water
        String result2 = tools.logWater(2);
        assertThat(result2).contains("Total today: 5 glass(es)");
    }

    @Test
    void testLogWaterFileCreated() {
        tools.logWater(2);

        String today = LocalDate.now().format(DATE_FMT);
        Path waterFile = tempDir.resolve("health").resolve("water_" + today + ".txt");
        assertThat(waterFile).exists();
    }

    @Test
    void testLogMeal() {
        String result = tools.logMeal("Chicken salad for lunch", 450);
        assertThat(result).contains("Logged meal: Chicken salad for lunch (450 cal)");

        String today = LocalDate.now().format(DATE_FMT);
        Path mealsFile = tempDir.resolve("health").resolve("meals_" + today + ".txt");
        assertThat(mealsFile).exists();
    }

    @Test
    void testLogExercise() {
        String result = tools.logExercise("Running", 30, "5K pace");
        assertThat(result).contains("Logged exercise: Running for 30 minutes");

        String today = LocalDate.now().format(DATE_FMT);
        Path exerciseFile = tempDir.resolve("health").resolve("exercise_" + today + ".txt");
        assertThat(exerciseFile).exists();
    }

    @Test
    void testLogExerciseWithNoNotes() {
        String result = tools.logExercise("Yoga", 45, "-");
        assertThat(result).contains("Logged exercise: Yoga for 45 minutes");
    }

    @Test
    void testLogWeight() {
        String result = tools.logWeight(75.5);
        assertThat(result).contains("Logged weight: 75.5 kg");

        Path weightFile = tempDir.resolve("health").resolve("weight_log.txt");
        assertThat(weightFile).exists();
    }

    @Test
    void testGetHealthSummary() {
        String today = LocalDate.now().format(DATE_FMT);

        // Log various things
        tools.logWater(3);
        tools.logMeal("Breakfast", 400);
        tools.logExercise("Running", 30, "morning jog");
        tools.logWeight(75.0);

        String summary = tools.getHealthSummary(today);
        assertThat(summary).contains("Health Summary for " + today);
        assertThat(summary).contains("Water: 3 glass(es)");
        assertThat(summary).contains("400 cal total");
        assertThat(summary).contains("Exercise:");
        assertThat(summary).contains("Running");
        assertThat(summary).contains("75.0 kg");
    }

    @Test
    void testGetHealthSummaryEmptyDay() {
        String summary = tools.getHealthSummary("2020-01-01");
        assertThat(summary).contains("Health Summary for 2020-01-01");
        assertThat(summary).contains("Water: No entries");
        assertThat(summary).contains("Meals: No entries");
        assertThat(summary).contains("Exercise: No entries");
    }

    @Test
    void testGetHealthTrend() throws IOException {
        // Write weight data directly for multiple days
        Path healthDir = tempDir.resolve("health");
        Files.createDirectories(healthDir);
        Path weightFile = healthDir.resolve("weight_log.txt");

        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(today.minusDays(2).format(DATE_FMT)).append("] 76.0 kg\n");
        sb.append("[").append(today.minusDays(1).format(DATE_FMT)).append("] 75.5 kg\n");
        sb.append("[").append(today.format(DATE_FMT)).append("] 75.0 kg\n");
        Files.writeString(weightFile, sb.toString(), StandardCharsets.UTF_8);

        String trend = tools.getHealthTrend("weight", 7);
        assertThat(trend).contains("Weight Trend (last 7 days)");
        assertThat(trend).contains("76.0 kg");
        assertThat(trend).contains("75.5 kg");
        assertThat(trend).contains("75.0 kg");
        assertThat(trend).contains("Total entries: 3 / 7 days");
    }

    @Test
    void testGetHealthTrendNoData() {
        String trend = tools.getHealthTrend("weight", 7);
        assertThat(trend).contains("No weight data found");
    }

    @Test
    void testSetAndGetHealthGoals() {
        String setResult = tools.setHealthGoal("water", "8", "glasses/day");
        assertThat(setResult).contains("Health goal set: water = 8 glasses/day");

        String goals = tools.getHealthGoals();
        assertThat(goals).contains("Health Goals");
        assertThat(goals).contains("water");
        assertThat(goals).contains("8");
        assertThat(goals).contains("glasses/day");
    }

    @Test
    void testSetHealthGoalReplaces() {
        tools.setHealthGoal("water", "6", "glasses/day");
        tools.setHealthGoal("water", "8", "glasses/day");

        String goals = tools.getHealthGoals();
        // Should only contain one water goal
        assertThat(goals).contains("8 glasses/day");
        // The old target should not appear (6 glasses won't be in the output)
        assertThat(goals).doesNotContain("6 glasses/day");
    }

    @Test
    void testGetHealthGoalsEmpty() {
        String goals = tools.getHealthGoals();
        assertThat(goals).contains("No health goals set yet");
    }
}
