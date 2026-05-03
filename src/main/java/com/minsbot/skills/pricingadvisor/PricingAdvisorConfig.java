package com.minsbot.skills.pricingadvisor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PricingAdvisorConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.pricingadvisor")
    public PricingAdvisorProperties pricingAdvisorProperties() { return new PricingAdvisorProperties(); }

    public static class PricingAdvisorProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
