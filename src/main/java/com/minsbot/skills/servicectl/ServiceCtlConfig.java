package com.minsbot.skills.servicectl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.servicectl")
    public ServiceCtlProperties serviceCtlProperties() { return new ServiceCtlProperties(); }

    public static class ServiceCtlProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
