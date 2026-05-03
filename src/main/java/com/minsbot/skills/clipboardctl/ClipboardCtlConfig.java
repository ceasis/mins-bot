package com.minsbot.skills.clipboardctl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClipboardCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.clipboardctl")
    public ClipboardCtlProperties clipboardCtlProperties() { return new ClipboardCtlProperties(); }
    public static class ClipboardCtlProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
