package com.minsbot.skills.networkdiag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NetworkDiagConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.networkdiag")
    public NetworkDiagProperties networkDiagProperties() { return new NetworkDiagProperties(); }
    public static class NetworkDiagProperties {
        private boolean enabled = false;
        private int httpTimeoutMs = 5000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getHttpTimeoutMs() { return httpTimeoutMs; }
        public void setHttpTimeoutMs(int v) { this.httpTimeoutMs = v; }
    }
}
