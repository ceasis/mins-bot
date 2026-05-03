package com.minsbot.skills.filerecover;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileRecoverConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.filerecover")
    public FileRecoverProperties fileRecoverProperties() { return new FileRecoverProperties(); }
    public static class FileRecoverProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
