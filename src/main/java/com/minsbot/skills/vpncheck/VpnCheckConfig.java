package com.minsbot.skills.vpncheck;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VpnCheckConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.vpncheck")
    public VpnCheckProperties vpnCheckProperties() { return new VpnCheckProperties(); }
    public static class VpnCheckProperties {
        private boolean enabled = false;
        private int timeoutMs = 5000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { this.timeoutMs = v; }
    }
}
