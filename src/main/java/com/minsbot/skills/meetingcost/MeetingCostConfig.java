package com.minsbot.skills.meetingcost;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MeetingCostConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.meetingcost")
    public MeetingCostProperties meetingCostProperties() { return new MeetingCostProperties(); }

    public static class MeetingCostProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
