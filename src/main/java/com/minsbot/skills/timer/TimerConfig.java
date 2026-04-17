package com.minsbot.skills.timer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimerConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.timer")
    public TimerProperties timerProperties() {
        return new TimerProperties();
    }

    public static class TimerProperties {
        private boolean enabled = false;
        private int maxTimers = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTimers() { return maxTimers; }
        public void setMaxTimers(int maxTimers) { this.maxTimers = maxTimers; }
    }
}
