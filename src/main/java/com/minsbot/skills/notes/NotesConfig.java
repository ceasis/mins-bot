package com.minsbot.skills.notes;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotesConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.notes")
    public NotesProperties notesProperties() {
        return new NotesProperties();
    }

    public static class NotesProperties {
        private boolean enabled = false;
        private String storageDir = "memory/notes";
        private int maxBodyChars = 50_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getMaxBodyChars() { return maxBodyChars; }
        public void setMaxBodyChars(int maxBodyChars) { this.maxBodyChars = maxBodyChars; }
    }
}
