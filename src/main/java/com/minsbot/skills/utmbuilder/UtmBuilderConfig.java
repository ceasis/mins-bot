package com.minsbot.skills.utmbuilder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UtmBuilderConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.utmbuilder")
    public UtmBuilderProperties utmBuilderProperties() {
        return new UtmBuilderProperties();
    }

    public static class UtmBuilderProperties {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
