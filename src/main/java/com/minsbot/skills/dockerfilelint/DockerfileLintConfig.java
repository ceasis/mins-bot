package com.minsbot.skills.dockerfilelint;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerfileLintConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.dockerfilelint")
    public DockerfileLintProperties dockerfileLintProperties() { return new DockerfileLintProperties(); }

    public static class DockerfileLintProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
