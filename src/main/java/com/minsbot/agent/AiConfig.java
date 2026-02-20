package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Spring AI configuration: ChatClient with tool calling + conversation memory.
 * Only activates when an OpenAI API key is configured (spring.ai.openai.api-key).
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${spring.ai.openai.api-key:NOT_SET}")
    private String apiKey;

    @PostConstruct
    public void diagnostics() {
        if ("NOT_SET".equals(apiKey) || apiKey.isBlank()) {
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║  spring.ai.openai.api-key is NOT SET                        ║");
            log.warn("║  ChatClient will NOT be created — AI features disabled.      ║");
            log.warn("║  Check that application-secrets.properties exists at the     ║");
            log.warn("║  project root and contains spring.ai.openai.api-key=sk-...   ║");
            log.warn("║  Working directory: {}",  System.getProperty("user.dir"));
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        } else {
            String masked = apiKey.length() > 12
                    ? apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4)
                    : "***";
            log.info("╔══════════════════════════════════════════════════════════════╗");
            log.info("║  spring.ai.openai.api-key loaded: {}",  masked);
            log.info("║  Spring AI ChatClient should be available.                   ║");
            log.info("╚══════════════════════════════════════════════════════════════╝");
        }
    }

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(500)
                .build();
    }

    @Bean
    public ChatClient chatClient(ObjectProvider<ChatClient.Builder> builderProvider, ChatMemory chatMemory) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        if (builder == null) {
            log.warn("[AiConfig] ChatClient.Builder not available — Spring AI auto-config did not create it.");
            return null;
        }
        log.info("[AiConfig] Creating ChatClient bean with memory advisor");
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .conversationId("mins-bot-local")
                        .build())
                .build();
    }
}
