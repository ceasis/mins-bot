package com.botsfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WeChatConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.wechat")
    public WeChatProperties weChatProperties() {
        return new WeChatProperties();
    }

    public static class WeChatProperties {
        private boolean enabled = false;
        private String appId = "";
        private String appSecret = "";
        private String token = "";
        private String encodingAesKey = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getEncodingAesKey() { return encodingAesKey; }
        public void setEncodingAesKey(String encodingAesKey) { this.encodingAesKey = encodingAesKey; }
    }
}
