package com.minsbot.skills.batterystatus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BatteryStatusConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.batterystatus")
    public BatteryStatusProperties batteryStatusProperties() { return new BatteryStatusProperties(); }
    public static class BatteryStatusProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
