package com.minsbot.skills.pacecalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaceCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.pacecalc")
    public PaceCalcProperties paceCalcProperties() { return new PaceCalcProperties(); }
    public static class PaceCalcProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
