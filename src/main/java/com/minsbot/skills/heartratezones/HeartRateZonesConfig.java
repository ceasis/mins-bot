package com.minsbot.skills.heartratezones;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HeartRateZonesConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.heartratezones")
    public HeartRateZonesProperties heartRateZonesProperties() { return new HeartRateZonesProperties(); }
    public static class HeartRateZonesProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
