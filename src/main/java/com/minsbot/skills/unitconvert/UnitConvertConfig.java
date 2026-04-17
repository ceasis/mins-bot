package com.minsbot.skills.unitconvert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnitConvertConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.unitconvert")
    public UnitConvertProperties unitConvertProperties() {
        return new UnitConvertProperties();
    }

    public static class UnitConvertProperties {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
