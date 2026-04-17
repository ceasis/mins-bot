package com.minsbot.skills.difftool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiffToolConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.difftool")
    public DiffToolProperties diffToolProperties() { return new DiffToolProperties(); }

    public static class DiffToolProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
