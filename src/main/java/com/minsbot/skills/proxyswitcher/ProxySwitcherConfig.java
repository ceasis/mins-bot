package com.minsbot.skills.proxyswitcher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Configuration
public class ProxySwitcherConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.proxyswitcher")
    public ProxySwitcherProperties proxySwitcherProperties() { return new ProxySwitcherProperties(); }
    public static class ProxySwitcherProperties {
        private boolean enabled = false;
        // preset name -> "host:port"
        private Map<String, String> presets = Map.of();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public Map<String, String> getPresets() { return presets; }
        public void setPresets(Map<String, String> v) { this.presets = v; }
    }
}
