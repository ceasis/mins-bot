package com.minsbot.skills.flashcardmaker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlashcardMakerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.flashcardmaker")
    public FlashcardMakerProperties flashcardMakerProperties() { return new FlashcardMakerProperties(); }
    public static class FlashcardMakerProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
