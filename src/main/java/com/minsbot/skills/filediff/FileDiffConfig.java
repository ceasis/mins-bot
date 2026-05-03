package com.minsbot.skills.filediff;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileDiffConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.filediff")
    public FileDiffProperties fileDiffProperties() { return new FileDiffProperties(); }
    public static class FileDiffProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
