package com.minsbot.skills.blogwriter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BlogWriterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.blogwriter")
    public BlogWriterProperties blogWriterProperties() { return new BlogWriterProperties(); }

    public static class BlogWriterProperties {
        private boolean enabled = false;
        private String storageDir = "memory/blog";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String v) { this.storageDir = v; }
    }
}
