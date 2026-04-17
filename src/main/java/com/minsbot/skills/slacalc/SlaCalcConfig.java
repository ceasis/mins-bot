package com.minsbot.skills.slacalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlaCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.slacalc")
    public SlaCalcProperties slaCalcProperties() { return new SlaCalcProperties(); }

    public static class SlaCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
