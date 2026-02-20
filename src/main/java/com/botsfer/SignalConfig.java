package com.botsfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SignalConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.signal")
    public SignalProperties signalProperties() {
        return new SignalProperties();
    }

    public static class SignalProperties {
        private boolean enabled = false;
        private String apiUrl = "http://localhost:8080";
        private String phoneNumber = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    }
}
