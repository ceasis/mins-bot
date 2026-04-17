package com.minsbot.skills.cvelookup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CveLookupConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.cvelookup")
    public CveLookupProperties cveLookupProperties() {
        return new CveLookupProperties();
    }

    public static class CveLookupProperties {
        private boolean enabled = false;
        private int timeoutMs = 15_000;
        private String apiBase = "https://services.nvd.nist.gov/rest/json/cves/2.0";
        private String apiKey = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}
