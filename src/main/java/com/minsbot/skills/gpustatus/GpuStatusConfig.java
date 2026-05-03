package com.minsbot.skills.gpustatus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GpuStatusConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.gpustatus")
    public GpuStatusProperties gpuStatusProperties() { return new GpuStatusProperties(); }
    public static class GpuStatusProperties {
        private boolean enabled = false;
        private int hotThresholdC = 80;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getHotThresholdC() { return hotThresholdC; }
        public void setHotThresholdC(int v) { this.hotThresholdC = v; }
    }
}
