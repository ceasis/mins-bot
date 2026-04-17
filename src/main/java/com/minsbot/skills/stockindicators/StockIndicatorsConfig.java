package com.minsbot.skills.stockindicators;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StockIndicatorsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.stockindicators")
    public StockIndicatorsProperties stockIndicatorsProperties() { return new StockIndicatorsProperties(); }

    public static class StockIndicatorsProperties {
        private boolean enabled = false;
        private int maxPricePoints = 100_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxPricePoints() { return maxPricePoints; }
        public void setMaxPricePoints(int maxPricePoints) { this.maxPricePoints = maxPricePoints; }
    }
}
