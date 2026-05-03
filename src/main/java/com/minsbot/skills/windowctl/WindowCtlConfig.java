package com.minsbot.skills.windowctl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WindowCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.windowctl")
    public WindowCtlProperties windowCtlProperties() { return new WindowCtlProperties(); }
    public static class WindowCtlProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
