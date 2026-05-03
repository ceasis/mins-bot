package com.minsbot.skills.proposalwriter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProposalWriterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.proposalwriter")
    public ProposalWriterProperties proposalWriterProperties() { return new ProposalWriterProperties(); }

    public static class ProposalWriterProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
