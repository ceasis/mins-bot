package com.minsbot.skills.taxcalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaxCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.taxcalc")
    public TaxCalcProperties taxCalcProperties() { return new TaxCalcProperties(); }

    public static class TaxCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
