package com.minsbot.skills.regexinferrer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegexInferrerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.regexinferrer")
    public RegexInferrerProperties regexInferrerProperties() { return new RegexInferrerProperties(); }
    public static class RegexInferrerProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
