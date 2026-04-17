package com.minsbot.skills.encoder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncoderConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.encoder")
    public EncoderProperties encoderProperties() {
        return new EncoderProperties();
    }

    public static class EncoderProperties {
        private boolean enabled = false;
        private int maxInputBytes = 5_000_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
    }
}
