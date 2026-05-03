package com.minsbot.skills.petentertainment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PetEntertainmentConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.petentertainment")
    public PetEntertainmentProperties petEntertainmentProperties() { return new PetEntertainmentProperties(); }
    public static class PetEntertainmentProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
