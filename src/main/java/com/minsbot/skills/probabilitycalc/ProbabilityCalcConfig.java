package com.minsbot.skills.probabilitycalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProbabilityCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.probabilitycalc")
    public ProbabilityCalcProperties probabilityCalcProperties() { return new ProbabilityCalcProperties(); }
    public static class ProbabilityCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
