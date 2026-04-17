package com.minsbot.skills.pomodoroplanner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PomodoroPlannerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.pomodoroplanner")
    public PomodoroPlannerProperties pomodoroPlannerProperties() { return new PomodoroPlannerProperties(); }
    public static class PomodoroPlannerProperties { private boolean enabled = false; public boolean isEnabled() { return enabled; } public void setEnabled(boolean enabled) { this.enabled = enabled; } }
}
