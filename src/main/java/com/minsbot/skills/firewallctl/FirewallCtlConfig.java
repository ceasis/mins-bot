package com.minsbot.skills.firewallctl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FirewallCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.firewallctl")
    public FirewallCtlProperties firewallCtlProperties() { return new FirewallCtlProperties(); }
    public static class FirewallCtlProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
