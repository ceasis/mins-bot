package com.minsbot.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChromeCdpConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.cdp")
    public ChromeCdpProperties chromeCdpProperties() {
        return new ChromeCdpProperties();
    }

    public static class ChromeCdpProperties {
        private boolean enabled = true;
        private int port = 9222;
        private String chromePath = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getChromePath() { return chromePath; }
        public void setChromePath(String chromePath) { this.chromePath = chromePath; }
    }
}
