package com.minsbot.skills.macrocalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MacroCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.macrocalc")
    public MacroCalcProperties macroCalcProperties() { return new MacroCalcProperties(); }
    public static class MacroCalcProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
