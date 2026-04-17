package com.minsbot.skills.numberwords;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NumberWordsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.numberwords")
    public NumberWordsProperties numberWordsProperties() { return new NumberWordsProperties(); }
    public static class NumberWordsProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
