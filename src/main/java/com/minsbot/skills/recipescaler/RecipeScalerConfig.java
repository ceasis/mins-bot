package com.minsbot.skills.recipescaler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecipeScalerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.recipescaler")
    public RecipeScalerProperties recipeScalerProperties() { return new RecipeScalerProperties(); }

    public static class RecipeScalerProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
