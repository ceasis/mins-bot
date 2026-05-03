package com.minsbot.skills.bigfilescan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BigFileScanConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.bigfilescan")
    public BigFileScanProperties bigFileScanProperties() { return new BigFileScanProperties(); }
    public static class BigFileScanProperties {
        private boolean enabled = false;
        private long defaultMinBytes = 50_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getDefaultMinBytes() { return defaultMinBytes; }
        public void setDefaultMinBytes(long v) { this.defaultMinBytes = v; }
    }
}
