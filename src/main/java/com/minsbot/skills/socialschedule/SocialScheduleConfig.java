package com.minsbot.skills.socialschedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocialScheduleConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.socialschedule")
    public SocialScheduleProperties socialScheduleProperties() {
        return new SocialScheduleProperties();
    }

    public static class SocialScheduleProperties {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
