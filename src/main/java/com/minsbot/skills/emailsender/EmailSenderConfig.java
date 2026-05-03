package com.minsbot.skills.emailsender;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmailSenderConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.emailsender")
    public EmailSenderProperties emailSenderProperties() { return new EmailSenderProperties(); }

    public static class EmailSenderProperties {
        private boolean enabled = false;
        private int timeoutMs = 15000;

        // Resend (preferred — simple HTTP API)
        private String resendApiKey = "";
        private String fromAddress = ""; // e.g. "you@yourdomain.com"
        private String fromName = "";    // optional display name

        // SMTP fallback
        private String smtpHost = "";
        private int smtpPort = 587;
        private String smtpUser = "";
        private String smtpPassword = "";
        private boolean smtpStartTls = true;

        // Daily send cap — abuse prevention
        private int dailyMaxSends = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getResendApiKey() { return resendApiKey; }
        public void setResendApiKey(String v) { this.resendApiKey = v; }
        public String getFromAddress() { return fromAddress; }
        public void setFromAddress(String v) { this.fromAddress = v; }
        public String getFromName() { return fromName; }
        public void setFromName(String v) { this.fromName = v; }
        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String v) { this.smtpHost = v; }
        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int v) { this.smtpPort = v; }
        public String getSmtpUser() { return smtpUser; }
        public void setSmtpUser(String v) { this.smtpUser = v; }
        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String v) { this.smtpPassword = v; }
        public boolean isSmtpStartTls() { return smtpStartTls; }
        public void setSmtpStartTls(boolean v) { this.smtpStartTls = v; }
        public int getDailyMaxSends() { return dailyMaxSends; }
        public void setDailyMaxSends(int v) { this.dailyMaxSends = v; }
    }
}
