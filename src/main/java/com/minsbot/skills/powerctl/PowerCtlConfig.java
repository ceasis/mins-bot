package com.minsbot.skills.powerctl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PowerCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.powerctl")
    public PowerCtlProperties powerCtlProperties() { return new PowerCtlProperties(); }
    public static class PowerCtlProperties {
        private boolean enabled = false;
        private boolean allowShutdown = false; // safety: disabled even when skill enabled
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isAllowShutdown() { return allowShutdown; }
        public void setAllowShutdown(boolean v) { this.allowShutdown = v; }
    }
}
