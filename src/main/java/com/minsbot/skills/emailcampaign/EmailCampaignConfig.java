package com.minsbot.skills.emailcampaign;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailCampaignConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.emailcampaign")
    public EmailCampaignProperties emailCampaignProperties() {
        return new EmailCampaignProperties();
    }

    public static class EmailCampaignProperties {
        private boolean enabled = false;
        private int maxBodyChars = 20000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxBodyChars() { return maxBodyChars; }
        public void setMaxBodyChars(int maxBodyChars) { this.maxBodyChars = maxBodyChars; }
    }
}
