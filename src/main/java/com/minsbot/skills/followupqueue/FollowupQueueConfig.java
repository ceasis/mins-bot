package com.minsbot.skills.followupqueue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FollowupQueueConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.followupqueue")
    public FollowupQueueProperties followupQueueProperties() { return new FollowupQueueProperties(); }

    public static class FollowupQueueProperties {
        private boolean enabled = false;
        private String storageDir = "memory/followups";
        private int[] cadenceDays = {3, 7, 14, 30};

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int[] getCadenceDays() { return cadenceDays; }
        public void setCadenceDays(int[] cadenceDays) { this.cadenceDays = cadenceDays; }
    }
}
