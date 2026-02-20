package com.botsfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessengerConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.messenger")
    public MessengerProperties messengerProperties() {
        return new MessengerProperties();
    }

    public static class MessengerProperties {
        private boolean enabled = false;
        private String pageAccessToken = "";
        private String verifyToken = "";
        private String appSecret = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPageAccessToken() { return pageAccessToken; }
        public void setPageAccessToken(String pageAccessToken) { this.pageAccessToken = pageAccessToken; }
        public String getVerifyToken() { return verifyToken; }
        public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    }
}
