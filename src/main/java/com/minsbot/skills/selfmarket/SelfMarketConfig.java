package com.minsbot.skills.selfmarket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SelfMarketConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.selfmarket")
    public SelfMarketProperties selfMarketProperties() { return new SelfMarketProperties(); }

    public static class SelfMarketProperties {
        private boolean enabled = false;
        private String storageDir = "memory/selfmarket";

        // Default brief for Mins Bot itself — overridable per-call
        private String product = "Mins Bot";
        private String tagline = "A floating desktop chatbot that connects to 9 messaging platforms and runs 100+ research/marketing/finance skills locally.";
        private String landingPage = "https://mins.io";
        private String audience = "indie hackers, small-business operators, freelancers";
        private List<String> contentSources = List.of();
        private List<String> competitorSites = List.of();
        private List<String> reviewSources = List.of();
        private List<String> trendingTopics = List.of("ai assistant", "desktop chatbot", "automation", "productivity");
        private List<String> postPlatforms = List.of("x", "linkedin", "threads", "bluesky");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public String getProduct() { return product; }
        public void setProduct(String product) { this.product = product; }
        public String getTagline() { return tagline; }
        public void setTagline(String tagline) { this.tagline = tagline; }
        public String getLandingPage() { return landingPage; }
        public void setLandingPage(String landingPage) { this.landingPage = landingPage; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public List<String> getContentSources() { return contentSources; }
        public void setContentSources(List<String> v) { this.contentSources = v; }
        public List<String> getCompetitorSites() { return competitorSites; }
        public void setCompetitorSites(List<String> v) { this.competitorSites = v; }
        public List<String> getReviewSources() { return reviewSources; }
        public void setReviewSources(List<String> v) { this.reviewSources = v; }
        public List<String> getTrendingTopics() { return trendingTopics; }
        public void setTrendingTopics(List<String> v) { this.trendingTopics = v; }
        public List<String> getPostPlatforms() { return postPlatforms; }
        public void setPostPlatforms(List<String> v) { this.postPlatforms = v; }
    }
}
