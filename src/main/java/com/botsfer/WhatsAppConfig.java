package com.botsfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WhatsAppConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.whatsapp")
    public WhatsAppProperties whatsAppProperties() {
        return new WhatsAppProperties();
    }

    public static class WhatsAppProperties {
        private boolean enabled = false;
        private String accessToken = "";
        private String phoneNumberId = "";
        private String verifyToken = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getPhoneNumberId() { return phoneNumberId; }
        public void setPhoneNumberId(String phoneNumberId) { this.phoneNumberId = phoneNumberId; }
        public String getVerifyToken() { return verifyToken; }
        public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }
    }
}
