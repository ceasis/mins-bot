package com.minsbot.skills.matrixops;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MatrixOpsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.matrixops")
    public MatrixOpsProperties matrixOpsProperties() { return new MatrixOpsProperties(); }
    public static class MatrixOpsProperties {
        private boolean enabled = false;
        private int maxDim = 100;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxDim() { return maxDim; }
        public void setMaxDim(int maxDim) { this.maxDim = maxDim; }
    }
}
