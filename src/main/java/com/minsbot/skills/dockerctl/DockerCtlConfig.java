package com.minsbot.skills.dockerctl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerCtlConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.dockerctl")
    public DockerCtlProperties dockerCtlProperties() { return new DockerCtlProperties(); }
    public static class DockerCtlProperties {
        private boolean enabled = false;
        private int timeoutSec = 30;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getTimeoutSec() { return timeoutSec; }
        public void setTimeoutSec(int v) { this.timeoutSec = v; }
    }
}
