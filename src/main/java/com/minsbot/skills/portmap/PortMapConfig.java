package com.minsbot.skills.portmap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PortMapConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.portmap")
    public PortMapProperties portMapProperties() { return new PortMapProperties(); }

    public static class PortMapProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
