package com.minsbot.skills.musicplayer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class MusicPlayerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.musicplayer")
    public MusicPlayerProperties musicPlayerProperties() { return new MusicPlayerProperties(); }
    public static class MusicPlayerProperties {
        private boolean enabled = false;
        // Folders to search when user says "play <name>". If empty, defaults to ~/Music.
        private List<String> libraryPaths = List.of();
        private List<String> audioExtensions = List.of("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma");
        // YouTube fallback when no local match. Opens default browser to YT search URL.
        private boolean youtubeFallback = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public List<String> getLibraryPaths() { return libraryPaths; }
        public void setLibraryPaths(List<String> v) { this.libraryPaths = v; }
        public List<String> getAudioExtensions() { return audioExtensions; }
        public void setAudioExtensions(List<String> v) { this.audioExtensions = v; }
        public boolean isYoutubeFallback() { return youtubeFallback; }
        public void setYoutubeFallback(boolean v) { this.youtubeFallback = v; }
    }
}
