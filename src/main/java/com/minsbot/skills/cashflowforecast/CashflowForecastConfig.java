package com.minsbot.skills.cashflowforecast;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CashflowForecastConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.cashflowforecast")
    public CashflowForecastProperties cashflowForecastProperties() { return new CashflowForecastProperties(); }
    public static class CashflowForecastProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
