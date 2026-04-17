package com.minsbot.skills.realestatecalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RealEstateCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.realestatecalc")
    public RealEstateCalcProperties realEstateCalcProperties() { return new RealEstateCalcProperties(); }

    public static class RealEstateCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
