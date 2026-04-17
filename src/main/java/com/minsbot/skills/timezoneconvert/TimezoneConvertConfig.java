package com.minsbot.skills.timezoneconvert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimezoneConvertConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.timezoneconvert")
    public TimezoneConvertProperties timezoneConvertProperties() { return new TimezoneConvertProperties(); }

    public static class TimezoneConvertProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
