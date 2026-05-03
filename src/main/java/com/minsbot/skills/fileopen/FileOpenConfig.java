package com.minsbot.skills.fileopen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileOpenConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.fileopen")
    public FileOpenProperties fileOpenProperties() { return new FileOpenProperties(); }
    public static class FileOpenProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
