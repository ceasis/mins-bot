package com.minsbot.skills.medicalunits;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MedicalUnitsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.medicalunits")
    public MedicalUnitsProperties medicalUnitsProperties() { return new MedicalUnitsProperties(); }

    public static class MedicalUnitsProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
