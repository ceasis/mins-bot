package com.minsbot.skills.imagemeta;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageMetaConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.imagemeta")
    public ImageMetaProperties imageMetaProperties() { return new ImageMetaProperties(); }

    public static class ImageMetaProperties {
        private boolean enabled = false;
        private long maxFileBytes = 200_000_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    }
}
