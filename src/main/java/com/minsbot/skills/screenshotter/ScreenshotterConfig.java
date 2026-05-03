package com.minsbot.skills.screenshotter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScreenshotterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.screenshotter")
    public ScreenshotterProperties screenshotterProperties() { return new ScreenshotterProperties(); }
    public static class ScreenshotterProperties {
        private boolean enabled = false;
        private String storageDir = "memory/screenshots";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String v) { this.storageDir = v; }
    }
}
