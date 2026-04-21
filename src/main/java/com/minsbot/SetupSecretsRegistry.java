package com.minsbot;

import java.util.List;

/**
 * Canonical property keys written to {@code application-secrets.properties} from the Setup tab.
 * Aligned with {@code application.properties} and {@code application-secrets.properties.example}.
 */
public final class SetupSecretsRegistry {

    public record SetupField(String propertyKey, String label, boolean mask) {}

    public record SetupGroup(String id, String title, List<SetupField> fields) {}

    private static final List<SetupGroup> GROUPS = List.of(
            new SetupGroup("llm", "Chat & LLM", List.of(
                    new SetupField("spring.ai.openai.api-key", "OpenAI API key", true),
                    new SetupField("app.anthropic.api-key", "Anthropic (Claude) API key", true),
                    new SetupField("gemini.api.key", "Google Gemini API key", true),
                    new SetupField("app.azure.openai.api-key", "Azure OpenAI API key", true),
                    new SetupField("app.azure.openai.endpoint", "Azure OpenAI endpoint URL", false),
                    new SetupField("app.cohere.api-key", "Cohere API key", true),
                    new SetupField("app.mistral.api-key", "Mistral API key", true),
                    new SetupField("app.groq.api-key", "Groq API key", true),
                    new SetupField("app.perplexity.api-key", "Perplexity API key", true),
                    new SetupField("app.xai.api-key", "xAI (Grok) API key", true),
                    new SetupField("app.together.api-key", "Together AI API key", true),
                    new SetupField("app.openrouter.api-key", "OpenRouter API key", true)
            )),
            new SetupGroup("voice", "Voice & TTS", List.of(
                    new SetupField("fish.audio.api.key", "Fish Audio API key", true),
                    new SetupField("fish.audio.reference.id", "Fish Audio voice / model ID", false),
                    new SetupField("app.elevenlabs.api-key", "ElevenLabs API key", true),
                    new SetupField("app.elevenlabs.voice-id", "ElevenLabs voice ID", false)
            )),
            new SetupGroup("search", "Web search", List.of(
                    new SetupField("serper.api.key", "Serper API key", true),
                    new SetupField("serpapi.api.key", "SerpAPI key", true)
            )),
            new SetupGroup("video-gen", "Video generation (text/image → video)", List.of(
                    new SetupField("app.sora.api-key", "OpenAI Sora API key (often same as OpenAI key)", true),
                    new SetupField("app.runway.api-key", "Runway API key", true),
                    new SetupField("app.luma.api-key", "Luma Dream Machine API key", true),
                    new SetupField("app.pika.api-key", "Pika Labs API key", true),
                    new SetupField("app.minimax.api-key", "MiniMax / Hailuo API key", true),
                    new SetupField("app.minimax.group-id", "MiniMax group ID", false),
                    new SetupField("app.kling.access-key", "Kling access key", true),
                    new SetupField("app.kling.secret-key", "Kling secret key", true),
                    new SetupField("app.stability.api-key", "Stability AI API key", true)
            )),
            new SetupGroup("video-aggregators", "Video aggregators (one key, many models)", List.of(
                    new SetupField("app.replicate.api-key", "Replicate API token", true),
                    new SetupField("app.falai.api-key", "fal.ai API key", true),
                    new SetupField("app.segmind.api-key", "Segmind API key", true)
            )),
            new SetupGroup("video-avatars", "Video avatars (talking heads)", List.of(
                    new SetupField("app.heygen.api-key", "HeyGen API key", true),
                    new SetupField("app.heygen.default-avatar-id", "HeyGen default avatar ID", false),
                    new SetupField("app.heygen.default-voice-id", "HeyGen default voice ID", false),
                    new SetupField("app.did.api-key", "D-ID API key", true),
                    new SetupField("app.synthesia.api-key", "Synthesia API key", true),
                    new SetupField("app.tavus.api-key", "Tavus API key", true),
                    new SetupField("app.captions.api-key", "Captions.ai API key", true),
                    new SetupField("app.higgsfield.api-key", "Higgsfield API key", true)
            )),
            new SetupGroup("google-integrations", "Google integrations (OAuth)", List.of(
                    new SetupField("spring.security.oauth2.client.registration.google.client-id",
                            "Google OAuth client ID (same key as TelliChat)", false),
                    new SetupField("spring.security.oauth2.client.registration.google.client-secret",
                            "Google OAuth client secret (same key as TelliChat)", true),
                    new SetupField("app.integrations.google.client-id", "Google OAuth client ID (fallback only)", false),
                    new SetupField("app.integrations.google.client-secret", "Google OAuth client secret (fallback only)", true)
            )),
            new SetupGroup("cloud", "AWS, GCP & Document AI", List.of(
                    new SetupField("aws.access.key", "AWS access key ID", true),
                    new SetupField("aws.secret.key", "AWS secret access key", true),
                    new SetupField("aws.region", "AWS region", false),
                    new SetupField("gcp.docai.api.key", "Google Document AI API key", true),
                    new SetupField("gcp.docai.processor.id", "Document AI processor ID", false)
            )),
            new SetupGroup("viber", "Viber", List.of(
                    new SetupField("app.viber.auth-token", "Viber auth token", true)
            )),
            new SetupGroup("telegram", "Telegram", List.of(
                    new SetupField("app.telegram.bot-token", "Telegram bot token", true)
            )),
            new SetupGroup("discord", "Discord", List.of(
                    new SetupField("app.discord.bot-token", "Discord bot token", true),
                    new SetupField("app.discord.application-id", "Discord application ID", false),
                    new SetupField("app.discord.public-key", "Discord public key", false)
            )),
            new SetupGroup("slack", "Slack", List.of(
                    new SetupField("app.slack.bot-token", "Slack bot token", true),
                    new SetupField("app.slack.signing-secret", "Slack signing secret", true),
                    new SetupField("app.slack.app-token", "Slack app-level token", true)
            )),
            new SetupGroup("whatsapp", "WhatsApp (Meta Cloud)", List.of(
                    new SetupField("app.whatsapp.access-token", "WhatsApp access token", true),
                    new SetupField("app.whatsapp.phone-number-id", "WhatsApp phone number ID", false),
                    new SetupField("app.whatsapp.verify-token", "WhatsApp verify token", true)
            )),
            new SetupGroup("messenger", "Facebook Messenger", List.of(
                    new SetupField("app.messenger.page-access-token", "Messenger page access token", true),
                    new SetupField("app.messenger.verify-token", "Messenger verify token", true),
                    new SetupField("app.messenger.app-secret", "Messenger app secret", true)
            )),
            new SetupGroup("line", "LINE", List.of(
                    new SetupField("app.line.channel-access-token", "LINE channel access token", true),
                    new SetupField("app.line.channel-secret", "LINE channel secret", true)
            )),
            new SetupGroup("teams", "Microsoft Teams", List.of(
                    new SetupField("app.teams.app-id", "Teams app (client) ID", false),
                    new SetupField("app.teams.app-password", "Teams app password", true),
                    new SetupField("app.teams.tenant-id", "Teams tenant ID", false)
            )),
            new SetupGroup("wechat", "WeChat Official Account", List.of(
                    new SetupField("app.wechat.app-id", "WeChat app ID", false),
                    new SetupField("app.wechat.app-secret", "WeChat app secret", true),
                    new SetupField("app.wechat.token", "WeChat token", true),
                    new SetupField("app.wechat.encoding-aes-key", "WeChat encoding AES key", true)
            )),
            new SetupGroup("email", "Email (SMTP & IMAP)", List.of(
                    new SetupField("spring.mail.username", "SMTP username", false),
                    new SetupField("spring.mail.password", "SMTP password", true),
                    new SetupField("app.email.imap.username", "IMAP username", false),
                    new SetupField("app.email.imap.password", "IMAP password", true)
            ))
    );

    private SetupSecretsRegistry() {}

    public static List<SetupGroup> groups() {
        return GROUPS;
    }

    public static boolean isAllowedKey(String key) {
        if (key == null || key.isBlank()) return false;
        for (SetupGroup g : GROUPS) {
            for (SetupField f : g.fields()) {
                if (f.propertyKey().equals(key)) return true;
            }
        }
        return false;
    }
}
