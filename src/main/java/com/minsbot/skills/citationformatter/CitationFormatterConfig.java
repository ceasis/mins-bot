package com.minsbot.skills.citationformatter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CitationFormatterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.citationformatter")
    public CitationFormatterProperties citationFormatterProperties() { return new CitationFormatterProperties(); }

    public static class CitationFormatterProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
