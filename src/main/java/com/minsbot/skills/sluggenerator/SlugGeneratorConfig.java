package com.minsbot.skills.sluggenerator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlugGeneratorConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.sluggenerator")
    public SlugGeneratorProperties slugGeneratorProperties() {
        return new SlugGeneratorProperties();
    }

    public static class SlugGeneratorProperties {
        private boolean enabled = false;
        private int maxInputChars = 2000;
        private int defaultMaxSlugLength = 80;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
        public int getDefaultMaxSlugLength() { return defaultMaxSlugLength; }
        public void setDefaultMaxSlugLength(int defaultMaxSlugLength) { this.defaultMaxSlugLength = defaultMaxSlugLength; }
    }
}
