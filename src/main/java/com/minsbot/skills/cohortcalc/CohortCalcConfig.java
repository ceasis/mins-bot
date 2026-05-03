package com.minsbot.skills.cohortcalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CohortCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.cohortcalc")
    public CohortCalcProperties cohortCalcProperties() { return new CohortCalcProperties(); }

    public static class CohortCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
