package com.minsbot.skills.adcopygen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdCopyGenConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.adcopygen")
    public AdCopyGenProperties adCopyGenProperties() {
        return new AdCopyGenProperties();
    }

    public static class AdCopyGenProperties {
        private boolean enabled = false;
        private int maxVariants = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxVariants() { return maxVariants; }
        public void setMaxVariants(int maxVariants) { this.maxVariants = maxVariants; }
    }
}
