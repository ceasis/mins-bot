package com.minsbot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoogleIntegrationConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.integrations.google")
    public GoogleIntegrationProperties googleIntegrationProperties() {
        return new GoogleIntegrationProperties();
    }

    public static final class GoogleIntegrationProperties {
        private String clientId = "";
        private String clientSecret = "";
        /** Full callback URL; empty = http://127.0.0.1:{server.port}/api/integrations/google/oauth2/callback */
        private String redirectUri = "";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId != null ? clientId : "";
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret != null ? clientSecret : "";
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri != null ? redirectUri : "";
        }

        public boolean isOAuthConfigured() {
            return !clientId.isBlank() && !clientSecret.isBlank();
        }
    }
}
