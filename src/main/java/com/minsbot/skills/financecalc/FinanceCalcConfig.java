package com.minsbot.skills.financecalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FinanceCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.financecalc")
    public FinanceCalcProperties financeCalcProperties() { return new FinanceCalcProperties(); }

    public static class FinanceCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
