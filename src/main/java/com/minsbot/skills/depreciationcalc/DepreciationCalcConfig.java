package com.minsbot.skills.depreciationcalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DepreciationCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.depreciationcalc")
    public DepreciationCalcProperties depreciationCalcProperties() { return new DepreciationCalcProperties(); }
    public static class DepreciationCalcProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
