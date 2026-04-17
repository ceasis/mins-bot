package com.minsbot.skills.breakevencalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BreakEvenCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.breakevencalc")
    public BreakEvenCalcProperties breakEvenCalcProperties() { return new BreakEvenCalcProperties(); }
    public static class BreakEvenCalcProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
