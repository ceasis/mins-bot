package com.minsbot.skills.jwtinspector;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtInspectorConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.jwtinspector")
    public JwtInspectorProperties jwtInspectorProperties() {
        return new JwtInspectorProperties();
    }

    public static class JwtInspectorProperties {
        private boolean enabled = false;
        private int maxTokenChars = 8192;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTokenChars() { return maxTokenChars; }
        public void setMaxTokenChars(int maxTokenChars) { this.maxTokenChars = maxTokenChars; }
    }
}
