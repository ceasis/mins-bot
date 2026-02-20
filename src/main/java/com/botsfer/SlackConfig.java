package com.botsfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlackConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.slack")
    public SlackProperties slackProperties() {
        return new SlackProperties();
    }

    public static class SlackProperties {
        private boolean enabled = false;
        private String botToken = "";
        private String signingSecret = "";
        private String appToken = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
        public String getAppToken() { return appToken; }
        public void setAppToken(String appToken) { this.appToken = appToken; }
    }
}
