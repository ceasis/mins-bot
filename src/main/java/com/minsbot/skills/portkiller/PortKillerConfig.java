package com.minsbot.skills.portkiller;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class PortKillerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.portkiller")
    public PortKillerProperties portKillerProperties() { return new PortKillerProperties(); }

    public static class PortKillerProperties {
        private boolean enabled = false;
        // Ports the bot itself uses — refuse to kill these by default
        private List<Integer> protectedPorts = List.of(8765);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<Integer> getProtectedPorts() { return protectedPorts; }
        public void setProtectedPorts(List<Integer> protectedPorts) { this.protectedPorts = protectedPorts; }
    }
}
