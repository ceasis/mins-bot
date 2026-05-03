package com.minsbot.skills.invoicegen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InvoiceGenConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.invoicegen")
    public InvoiceGenProperties invoiceGenProperties() { return new InvoiceGenProperties(); }

    public static class InvoiceGenProperties {
        private boolean enabled = false;
        private String storageDir = "memory/invoices";
        private String currency = "USD";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
    }
}
