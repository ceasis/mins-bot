package com.minsbot.skills.robotschecker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RobotsCheckerConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.robotschecker")
    public RobotsCheckerProperties robotsCheckerProperties() {
        return new RobotsCheckerProperties();
    }

    public static class RobotsCheckerProperties {
        private boolean enabled = false;
        private int timeoutMs = 10_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
