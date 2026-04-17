package com.minsbot.skills.hashtagsuggest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HashtagSuggestConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.hashtagsuggest")
    public HashtagSuggestProperties hashtagSuggestProperties() {
        return new HashtagSuggestProperties();
    }

    public static class HashtagSuggestProperties {
        private boolean enabled = false;
        private int maxTextChars = 50_000;
        private int defaultTopN = 15;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
        public int getDefaultTopN() { return defaultTopN; }
        public void setDefaultTopN(int defaultTopN) { this.defaultTopN = defaultTopN; }
    }
}
