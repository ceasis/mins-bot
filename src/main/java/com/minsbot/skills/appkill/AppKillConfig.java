package com.minsbot.skills.appkill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class AppKillConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.appkill")
    public AppKillProperties appKillProperties() { return new AppKillProperties(); }
    public static class AppKillProperties {
        private boolean enabled = false;
        private List<String> protectedNames = List.of("explorer", "system", "winlogon", "csrss", "java", "javaw");
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public List<String> getProtectedNames() { return protectedNames; }
        public void setProtectedNames(List<String> v) { this.protectedNames = v; }
    }
}
