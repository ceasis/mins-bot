package com.minsbot.skills.autostartmanager;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AutoStartManagerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.autostartmanager")
    public AutoStartManagerProperties autoStartManagerProperties() { return new AutoStartManagerProperties(); }
    public static class AutoStartManagerProperties {
        private boolean enabled = false;
        // Name used when the user says "make yourself auto-start"
        private String selfEntryName = "MinsBot";
        // Command to run for self-autostart. If blank, the service auto-detects
        // <projectRoot>/restart.bat (Windows) or builds a "java -jar" line.
        private String selfCommand = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getSelfEntryName() { return selfEntryName; }
        public void setSelfEntryName(String v) { this.selfEntryName = v; }
        public String getSelfCommand() { return selfCommand; }
        public void setSelfCommand(String v) { this.selfCommand = v; }
    }
}
