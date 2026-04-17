package com.minsbot.skills.physicscalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PhysicsCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.physicscalc")
    public PhysicsCalcProperties physicsCalcProperties() { return new PhysicsCalcProperties(); }
    public static class PhysicsCalcProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
