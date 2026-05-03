package com.minsbot.skills.buildwatcher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BuildWatcherConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.buildwatcher")
    public BuildWatcherProperties buildWatcherProperties() { return new BuildWatcherProperties(); }
    public static class BuildWatcherProperties {
        private boolean enabled = false;
        private int timeoutSec = 600;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getTimeoutSec() { return timeoutSec; }
        public void setTimeoutSec(int v) { this.timeoutSec = v; }
    }
}
