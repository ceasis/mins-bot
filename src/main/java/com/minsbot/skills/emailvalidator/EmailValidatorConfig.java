package com.minsbot.skills.emailvalidator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class EmailValidatorConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.emailvalidator")
    public EmailValidatorProperties emailValidatorProperties() {
        return new EmailValidatorProperties();
    }

    public static class EmailValidatorProperties {
        private boolean enabled = false;
        private int timeoutMs = 5000;
        private boolean checkMx = true;
        private List<String> disposableDomains = List.of(
                "mailinator.com","10minutemail.com","guerrillamail.com","guerrillamail.info","sharklasers.com",
                "tempmail.com","temp-mail.org","yopmail.com","trashmail.com","throwawaymail.com","fakeinbox.com",
                "maildrop.cc","dispostable.com","getnada.com","mohmal.com","emailondeck.com","mailnesia.com"
        );

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public boolean isCheckMx() { return checkMx; }
        public void setCheckMx(boolean checkMx) { this.checkMx = checkMx; }
        public List<String> getDisposableDomains() { return disposableDomains; }
        public void setDisposableDomains(List<String> disposableDomains) { this.disposableDomains = disposableDomains; }
    }
}
