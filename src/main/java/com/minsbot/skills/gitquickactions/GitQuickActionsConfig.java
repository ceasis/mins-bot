package com.minsbot.skills.gitquickactions;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitQuickActionsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.gitquickactions")
    public GitQuickActionsProperties gitQuickActionsProperties() { return new GitQuickActionsProperties(); }
    public static class GitQuickActionsProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
