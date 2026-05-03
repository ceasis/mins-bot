package com.minsbot.skills.processkiller;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class ProcessKillerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.processkiller")
    public ProcessKillerProperties processKillerProperties() { return new ProcessKillerProperties(); }

    public static class ProcessKillerProperties {
        private boolean enabled = false;
        // Process names that must NEVER be killed (case-insensitive substring match)
        private List<String> protectedNames = List.of("java", "javaw", "system", "explorer", "csrss", "winlogon", "wininit", "lsass", "services");
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public List<String> getProtectedNames() { return protectedNames; }
        public void setProtectedNames(List<String> v) { this.protectedNames = v; }
    }
}
