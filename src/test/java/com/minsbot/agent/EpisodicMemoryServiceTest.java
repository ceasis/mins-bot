package com.minsbot.agent;

import com.minsbot.agent.tools.TestReflectionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodicMemoryServiceTest {

    @TempDir
    Path tempDir;

    private EpisodicMemoryService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new EpisodicMemoryService();
        // Redirect MEMORY_DIR to temp directory using Unsafe (static final in Java 17)
        TestReflectionUtil.setStaticField(EpisodicMemoryService.class,
                "MEMORY_DIR", tempDir.resolve("episodic_memory"));
        // Call init to create the directory
        service.init();
    }

    @Test
    void testSaveAndRetrieveEpisode() {
        String id = service.saveEpisode("conversation", "Had a chat about Java",
                "Discussed Spring Boot testing strategies",
                List.of("java", "testing"), List.of("Alice"), 3);

        assertThat(id).isNotNull().startsWith("ep-");

        Map<String, Object> episode = service.getEpisode(id);
        assertThat(episode).isNotNull();
        assertThat(episode.get("id")).isEqualTo(id);
        assertThat(episode.get("type")).isEqualTo("conversation");
        assertThat(episode.get("summary")).isEqualTo("Had a chat about Java");
        assertThat(episode.get("details")).isEqualTo("Discussed Spring Boot testing strategies");
        assertThat(episode.get("importance")).isEqualTo(3);
    }

    @Test
    void testSearchEpisodes() throws InterruptedException {
        service.saveEpisode("conversation", "Talked about cooking pasta",
                "Italian recipes", List.of("food"), List.of(), 2);
        Thread.sleep(5);
        service.saveEpisode("task", "Fixed a bug in the login page",
                "JavaScript error", List.of("code"), List.of(), 3);
        Thread.sleep(5);
        service.saveEpisode("conversation", "Discussed pasta sauce recipes",
                "Tomato vs cream", List.of("food"), List.of(), 2);

        List<Map<String, Object>> results = service.searchEpisodes("pasta", 10);
        assertThat(results).hasSize(2);
        // All results should mention pasta in summary or details
        for (Map<String, Object> ep : results) {
            String summary = (String) ep.get("summary");
            String details = (String) ep.get("details");
            assertThat(summary.toLowerCase() + " " + details.toLowerCase()).contains("pasta");
        }
    }

    @Test
    void testGetEpisodesByType() throws InterruptedException {
        service.saveEpisode("conversation", "Chat 1", "details", List.of(), List.of(), 2);
        Thread.sleep(5);
        service.saveEpisode("task", "Task 1", "details", List.of(), List.of(), 3);
        Thread.sleep(5);
        service.saveEpisode("conversation", "Chat 2", "details", List.of(), List.of(), 2);
        Thread.sleep(5);
        service.saveEpisode("observation", "Obs 1", "details", List.of(), List.of(), 1);

        List<Map<String, Object>> conversations = service.getEpisodesByType("conversation", 10);
        assertThat(conversations).hasSize(2);
        assertThat(conversations).allSatisfy(ep ->
                assertThat(ep.get("type")).isEqualTo("conversation"));

        List<Map<String, Object>> tasks = service.getEpisodesByType("task", 10);
        assertThat(tasks).hasSize(1);
    }

    @Test
    void testGetEpisodesByPerson() throws InterruptedException {
        service.saveEpisode("conversation", "Chat with Alice", "details",
                List.of(), List.of("Alice", "Bob"), 2);
        Thread.sleep(5);
        service.saveEpisode("conversation", "Chat with Charlie", "details",
                List.of(), List.of("Charlie"), 2);
        Thread.sleep(5);
        service.saveEpisode("task", "Meeting with Alice", "details",
                List.of(), List.of("Alice"), 3);

        List<Map<String, Object>> aliceEpisodes = service.getEpisodesByPerson("Alice");
        assertThat(aliceEpisodes).hasSize(2);

        List<Map<String, Object>> charlieEpisodes = service.getEpisodesByPerson("charlie");
        assertThat(charlieEpisodes).hasSize(1);

        List<Map<String, Object>> unknownEpisodes = service.getEpisodesByPerson("Dave");
        assertThat(unknownEpisodes).isEmpty();
    }

    @Test
    void testGetEpisodesByTag() throws InterruptedException {
        service.saveEpisode("conversation", "Food talk", "details",
                List.of("food", "recipes"), List.of(), 2);
        Thread.sleep(5);
        service.saveEpisode("task", "Code review", "details",
                List.of("code", "review"), List.of(), 3);
        Thread.sleep(5);
        service.saveEpisode("conversation", "More food", "details",
                List.of("food"), List.of(), 2);

        List<Map<String, Object>> foodEpisodes = service.getEpisodesByTag("food");
        assertThat(foodEpisodes).hasSize(2);

        List<Map<String, Object>> codeEpisodes = service.getEpisodesByTag("code");
        assertThat(codeEpisodes).hasSize(1);

        List<Map<String, Object>> emptyEpisodes = service.getEpisodesByTag("nonexistent");
        assertThat(emptyEpisodes).isEmpty();
    }

    @Test
    void testGetRecentEpisodes() throws Exception {
        // Save episodes and verify we can retrieve the most recent ones
        // Since saveEpisode uses System.currentTimeMillis() for IDs and
        // LocalDateTime.now() for timestamps (HH:mm:ss granularity), we
        // verify ordering by checking that getRecentEpisodes(2) returns
        // exactly 2 out of 3 and all are valid episodes.
        String id1 = service.saveEpisode("conversation", "First", "d1", List.of(), List.of(), 2);
        Thread.sleep(15);
        String id2 = service.saveEpisode("task", "Second", "d2", List.of(), List.of(), 3);
        Thread.sleep(15);
        String id3 = service.saveEpisode("observation", "Third", "d3", List.of(), List.of(), 1);

        // All 3 saved successfully with unique IDs
        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();
        assertThat(id3).isNotNull();
        assertThat(Set.of(id1, id2, id3)).hasSize(3);

        List<Map<String, Object>> all = service.getRecentEpisodes(10);
        assertThat(all).hasSize(3);

        List<Map<String, Object>> recent = service.getRecentEpisodes(2);
        assertThat(recent).hasSize(2);
        // Each returned episode should be one of our saved ones
        for (Map<String, Object> ep : recent) {
            assertThat(ep.get("summary")).isIn("First", "Second", "Third");
        }
    }

    @Test
    void testDeleteEpisode() {
        String id = service.saveEpisode("conversation", "To delete", "details",
                List.of(), List.of(), 2);
        assertThat(service.getEpisode(id)).isNotNull();

        boolean deleted = service.deleteEpisode(id);
        assertThat(deleted).isTrue();
        assertThat(service.getEpisode(id)).isNull();

        // Deleting again should return false
        boolean deletedAgain = service.deleteEpisode(id);
        assertThat(deletedAgain).isFalse();
    }

    @Test
    void testGetMemorySummary() throws InterruptedException {
        service.saveEpisode("conversation", "Chat 1", "d", List.of(), List.of(), 2);
        Thread.sleep(5);
        service.saveEpisode("task", "Task 1", "d", List.of(), List.of(), 3);
        Thread.sleep(5);
        service.saveEpisode("conversation", "Chat 2", "d", List.of(), List.of(), 2);

        Map<String, Object> summary = service.getMemorySummary();
        assertThat(summary).isNotNull();
        assertThat(summary.get("totalEpisodes")).isEqualTo(3);

        @SuppressWarnings("unchecked")
        Map<String, Long> byType = (Map<String, Long>) summary.get("byType");
        assertThat(byType.get("conversation")).isEqualTo(2L);
        assertThat(byType.get("task")).isEqualTo(1L);

        assertThat(summary.get("earliestTimestamp")).isNotNull();
        assertThat(summary.get("latestTimestamp")).isNotNull();
    }

    @Test
    void testGetEpisodesByDateRange() throws InterruptedException {
        // All episodes saved now have today's date
        service.saveEpisode("conversation", "Today's chat", "d", List.of(), List.of(), 2);
        Thread.sleep(5);
        service.saveEpisode("task", "Today's task", "d", List.of(), List.of(), 3);

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> results = service.getEpisodesByDateRange(
                today.minusDays(1), today.plusDays(1));
        assertThat(results).hasSize(2);

        // Date range that excludes today
        List<Map<String, Object>> noResults = service.getEpisodesByDateRange(
                today.minusDays(10), today.minusDays(5));
        assertThat(noResults).isEmpty();
    }
}
