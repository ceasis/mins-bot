package com.minsbot.skills.applauncher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Configuration
public class AppLauncherConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.applauncher")
    public AppLauncherProperties appLauncherProperties() { return new AppLauncherProperties(); }
    public static class AppLauncherProperties {
        private boolean enabled = false;
        // friendly name -> command/path (e.g. "chrome" -> "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe")
        private Map<String, String> registry = Map.of();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public Map<String, String> getRegistry() { return registry; }
        public void setRegistry(Map<String, String> v) { this.registry = v; }
    }
}
