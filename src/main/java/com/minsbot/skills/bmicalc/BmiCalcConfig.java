package com.minsbot.skills.bmicalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BmiCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.bmicalc")
    public BmiCalcProperties bmiCalcProperties() { return new BmiCalcProperties(); }

    public static class BmiCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
