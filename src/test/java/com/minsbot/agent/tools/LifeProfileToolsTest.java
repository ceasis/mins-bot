package com.minsbot.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LifeProfileToolsTest {

    @TempDir
    Path tempDir;

    private LifeProfileTools tools;

    @BeforeEach
    void setUp() throws Exception {
        ToolExecutionNotifier notifier = new ToolExecutionNotifier();
        tools = new LifeProfileTools(notifier);

        // Redirect LIFE_PROFILE_PATH to temp directory using Unsafe (static final in Java 17)
        TestReflectionUtil.setStaticField(LifeProfileTools.class,
                "LIFE_PROFILE_PATH", tempDir.resolve("life_profile.txt"));
    }

    @Test
    void testGetLifeProfile() {
        // First call should create the default template
        String profile = tools.getLifeProfile();
        assertThat(profile).contains("# Life Profile");
        assertThat(profile).contains("## Routines");
        assertThat(profile).contains("## Preferences");
        assertThat(profile).contains("## Relationships");
        assertThat(profile).contains("## Goals");
        assertThat(profile).contains("## Health");
        assertThat(profile).contains("## Finance");
        assertThat(profile).contains("## Locations");
        assertThat(profile).contains("## Vehicles");
        assertThat(profile).contains("## Pets");
        assertThat(profile).contains("## ImportantDates");
        assertThat(profile).contains("## Notes");
    }

    @Test
    void testUpdateSection() {
        // Trigger file creation
        tools.getLifeProfile();

        String result = tools.updateLifeProfileSection("Routines",
                "- Wake up at 6:00 AM\n- Morning jog at 6:30 AM\n- Breakfast at 7:30 AM");
        assertThat(result).contains("updated");
        assertThat(result).contains("Routines");

        // Read it back
        String section = tools.getLifeProfileSection("Routines");
        assertThat(section).contains("Wake up at 6:00 AM");
        assertThat(section).contains("Morning jog at 6:30 AM");
        assertThat(section).contains("Breakfast at 7:30 AM");
    }

    @Test
    void testUpdateSectionDoesNotAffectOthers() {
        tools.getLifeProfile();

        tools.updateLifeProfileSection("Goals", "- Learn guitar\n- Run a marathon");

        // Other sections should still exist
        String profile = tools.getLifeProfile();
        assertThat(profile).contains("## Routines");
        assertThat(profile).contains("## Preferences");
        assertThat(profile).contains("## Goals");
        assertThat(profile).contains("Learn guitar");
    }

    @Test
    void testAppendToSection() {
        tools.getLifeProfile();

        // Append to a section that starts with just "-"
        String result = tools.appendToLifeProfileSection("Relationships",
                "Brother: Marco, birthday June 15");
        assertThat(result).contains("added entry");

        String section = tools.getLifeProfileSection("Relationships");
        assertThat(section).contains("Brother: Marco, birthday June 15");

        // Append another entry
        tools.appendToLifeProfileSection("Relationships",
                "Sister: Maria, birthday March 20");

        section = tools.getLifeProfileSection("Relationships");
        assertThat(section).contains("Brother: Marco, birthday June 15");
        assertThat(section).contains("Sister: Maria, birthday March 20");
    }

    @Test
    void testRemoveFromSection() {
        tools.getLifeProfile();

        // Add entries first
        tools.appendToLifeProfileSection("Goals", "Learn guitar by December");
        tools.appendToLifeProfileSection("Goals", "Run a marathon in spring");
        tools.appendToLifeProfileSection("Goals", "Read 24 books this year");

        // Remove one
        String result = tools.removeFromLifeProfileSection("Goals", "marathon");
        assertThat(result).contains("removed matching entries");

        String section = tools.getLifeProfileSection("Goals");
        assertThat(section).contains("Learn guitar");
        assertThat(section).contains("Read 24 books");
        assertThat(section).doesNotContain("marathon");
    }

    @Test
    void testRemoveFromSectionResetsToPlaceholder() {
        tools.getLifeProfile();

        tools.appendToLifeProfileSection("Pets", "Dog: Buddy");

        // Remove the only entry — the line "- Dog: Buddy" contains "Buddy"
        tools.removeFromLifeProfileSection("Pets", "Buddy");

        String section = tools.getLifeProfileSection("Pets");
        // Should reset to "-" placeholder
        assertThat(section.trim()).isEqualTo("## Pets\n-");
    }

    @Test
    void testSearchLifeProfile() {
        tools.getLifeProfile();

        tools.appendToLifeProfileSection("Relationships", "Brother: Marco, lives in Manila");
        tools.appendToLifeProfileSection("Locations", "Home: Manila, Philippines");

        String result = tools.searchLifeProfile("Manila");
        assertThat(result).contains("Found 2 match(es)");
        assertThat(result).contains("[Relationships]");
        assertThat(result).contains("[Locations]");
        assertThat(result).contains("Manila");
    }

    @Test
    void testSearchLifeProfileCaseInsensitive() {
        tools.getLifeProfile();

        tools.appendToLifeProfileSection("Goals", "Learn PYTHON programming");

        String result = tools.searchLifeProfile("python");
        assertThat(result).contains("Found");
        assertThat(result).contains("PYTHON");
    }

    @Test
    void testSearchLifeProfileNoMatch() {
        tools.getLifeProfile();

        String result = tools.searchLifeProfile("xyznonexistent");
        assertThat(result).contains("No matches found");
    }

    @Test
    void testGetSpecificSection() {
        tools.getLifeProfile();

        tools.updateLifeProfileSection("Finance",
                "- Monthly budget: $3000\n- Savings rate: 20%");

        String section = tools.getLifeProfileSection("Finance");
        assertThat(section).contains("## Finance");
        assertThat(section).contains("Monthly budget: $3000");
        assertThat(section).contains("Savings rate: 20%");
    }

    @Test
    void testGetNonexistentSection() {
        tools.getLifeProfile();

        String section = tools.getLifeProfileSection("NonexistentSection");
        assertThat(section).contains("not found");
    }
}
