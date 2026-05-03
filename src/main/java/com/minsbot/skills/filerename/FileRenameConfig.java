package com.minsbot.skills.filerename;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileRenameConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.filerename")
    public FileRenameProperties fileRenameProperties() { return new FileRenameProperties(); }
    public static class FileRenameProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
