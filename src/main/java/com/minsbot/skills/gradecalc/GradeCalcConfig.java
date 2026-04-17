package com.minsbot.skills.gradecalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GradeCalcConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.gradecalc")
    public GradeCalcProperties gradeCalcProperties() { return new GradeCalcProperties(); }

    public static class GradeCalcProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
