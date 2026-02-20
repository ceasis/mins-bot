package com.minsbot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElevenLabsConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.elevenlabs")
    public ElevenLabsProperties elevenLabsProperties() {
        return new ElevenLabsProperties();
    }

    public static class ElevenLabsProperties {
        private boolean enabled = false;
        private String apiKey = "";
        /** Voice ID from https://elevenlabs.io/app/voice-lab (e.g. Rachel, Adam). */
        private String voiceId = "";
        /** Model ID, e.g. eleven_multilingual_v2 (default). */
        private String modelId = "eleven_multilingual_v2";
        /** Output format: wav_44100 for Java playback, or mp3_44100_128. */
        private String outputFormat = "wav_44100";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getVoiceId() { return voiceId; }
        public void setVoiceId(String voiceId) { this.voiceId = voiceId; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getOutputFormat() { return outputFormat; }
        public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
    }
}
