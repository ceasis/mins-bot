package com.botsfer.skills.diskscan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DiskScanConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.diskscan")
    public DiskScanProperties diskScanProperties() {
        return new DiskScanProperties();
    }

    public static class DiskScanProperties {
        private boolean enabled = false;
        private int maxDepth = 20;
        private int maxResults = 500;
        private List<String> blockedPaths = List.of();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public List<String> getBlockedPaths() { return blockedPaths; }
        public void setBlockedPaths(List<String> blockedPaths) { this.blockedPaths = blockedPaths; }
    }
}
