package com.minsbot.skills.netinfo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class NetInfoConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.netinfo")
    public NetInfoProperties netInfoProperties() {
        return new NetInfoProperties();
    }

    public static class NetInfoProperties {
        private boolean enabled = false;
        private int portCheckTimeoutMs = 2000;
        private List<String> allowedPortCheckHosts = List.of();
        private String publicIpUrl = "https://api.ipify.org";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPortCheckTimeoutMs() { return portCheckTimeoutMs; }
        public void setPortCheckTimeoutMs(int portCheckTimeoutMs) { this.portCheckTimeoutMs = portCheckTimeoutMs; }
        public List<String> getAllowedPortCheckHosts() { return allowedPortCheckHosts; }
        public void setAllowedPortCheckHosts(List<String> allowedPortCheckHosts) { this.allowedPortCheckHosts = allowedPortCheckHosts; }
        public String getPublicIpUrl() { return publicIpUrl; }
        public void setPublicIpUrl(String publicIpUrl) { this.publicIpUrl = publicIpUrl; }
    }
}
