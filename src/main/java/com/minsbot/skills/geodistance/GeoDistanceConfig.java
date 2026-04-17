package com.minsbot.skills.geodistance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeoDistanceConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.geodistance")
    public GeoDistanceProperties geoDistanceProperties() { return new GeoDistanceProperties(); }
    public static class GeoDistanceProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
