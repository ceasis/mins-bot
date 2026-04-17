package com.minsbot.skills.fakedatagen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FakeDataGenConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.fakedatagen")
    public FakeDataGenProperties fakeDataGenProperties() { return new FakeDataGenProperties(); }
    public static class FakeDataGenProperties {
        private boolean enabled = false;
        private int maxRows = 10_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    }
}
