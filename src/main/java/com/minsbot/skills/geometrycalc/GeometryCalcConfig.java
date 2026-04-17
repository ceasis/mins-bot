package com.minsbot.skills.geometrycalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeometryCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.geometrycalc")
    public GeometryCalcProperties geometryCalcProperties() { return new GeometryCalcProperties(); }

    public static class GeometryCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
