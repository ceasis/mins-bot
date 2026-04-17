package com.minsbot.skills.abtestcalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AbTestCalcConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.abtestcalc")
    public AbTestCalcProperties abTestCalcProperties() {
        return new AbTestCalcProperties();
    }

    public static class AbTestCalcProperties {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
