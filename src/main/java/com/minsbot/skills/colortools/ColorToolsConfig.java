package com.minsbot.skills.colortools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ColorToolsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.colortools")
    public ColorToolsProperties colorToolsProperties() { return new ColorToolsProperties(); }

    public static class ColorToolsProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
