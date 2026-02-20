package com.botsfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LineConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.line")
    public LineProperties lineProperties() {
        return new LineProperties();
    }

    public static class LineProperties {
        private boolean enabled = false;
        private String channelAccessToken = "";
        private String channelSecret = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getChannelAccessToken() { return channelAccessToken; }
        public void setChannelAccessToken(String channelAccessToken) { this.channelAccessToken = channelAccessToken; }
        public String getChannelSecret() { return channelSecret; }
        public void setChannelSecret(String channelSecret) { this.channelSecret = channelSecret; }
    }
}
