package com.minsbot.skills.writingtools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WritingToolsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.writingtools")
    public WritingToolsProperties writingToolsProperties() { return new WritingToolsProperties(); }

    public static class WritingToolsProperties {
        private boolean enabled = false;
        private int maxTextChars = 500_000;
        private int wordsPerMinute = 238;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
        public int getWordsPerMinute() { return wordsPerMinute; }
        public void setWordsPerMinute(int wordsPerMinute) { this.wordsPerMinute = wordsPerMinute; }
    }
}
