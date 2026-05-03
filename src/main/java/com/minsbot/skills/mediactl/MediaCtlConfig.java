package com.minsbot.skills.mediactl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MediaCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.mediactl")
    public MediaCtlProperties mediaCtlProperties() { return new MediaCtlProperties(); }
    public static class MediaCtlProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
