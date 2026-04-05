package com.minsbot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FishAudioConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.fishaudio")
    public FishAudioProperties fishAudioProperties() {
        return new FishAudioProperties();
    }

    public static class FishAudioProperties {
        private boolean enabled = false;
        private String apiKey = "";
        /** Reference voice model ID from https://fish.audio — leave blank for default voice. */
        private String referenceId = "";
        /** Model: s1 or s2-pro (default). */
        private String model = "s2-pro";
        /** Output format: mp3, wav, pcm, opus. */
        private String format = "pcm";
        /** Sample rate in Hz (default 24000 for PCM streaming). */
        private int sampleRate = 24000;
        /** Female voice reference ID for gender-matched TTS. */
        private String femaleReferenceId = "";
        /** Male voice reference ID for gender-matched TTS. */
        private String maleReferenceId = "";
        /** Optional prosody.speed (e.g. 1.1 for Jarvis-style); omit from API when null. */
        private Double prosodySpeed;
        /** Optional prosody.volume in dB (0 = no change). */
        private Double prosodyVolume;
        /** S2-Pro: prosody.normalize_loudness — matches Fish web UI. */
        private Boolean normalizeLoudness;
        /** API "normalize" — text normalization for numbers etc. */
        private Boolean normalizeText;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getReferenceId() { return referenceId; }
        public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
        public String getFemaleReferenceId() { return femaleReferenceId; }
        public void setFemaleReferenceId(String femaleReferenceId) { this.femaleReferenceId = femaleReferenceId; }
        public String getMaleReferenceId() { return maleReferenceId; }
        public void setMaleReferenceId(String maleReferenceId) { this.maleReferenceId = maleReferenceId; }
        public Double getProsodySpeed() { return prosodySpeed; }
        public void setProsodySpeed(Double prosodySpeed) { this.prosodySpeed = prosodySpeed; }
        public Double getProsodyVolume() { return prosodyVolume; }
        public void setProsodyVolume(Double prosodyVolume) { this.prosodyVolume = prosodyVolume; }
        public Boolean getNormalizeLoudness() { return normalizeLoudness; }
        public void setNormalizeLoudness(Boolean normalizeLoudness) { this.normalizeLoudness = normalizeLoudness; }
        public Boolean getNormalizeText() { return normalizeText; }
        public void setNormalizeText(Boolean normalizeText) { this.normalizeText = normalizeText; }
    }
}
