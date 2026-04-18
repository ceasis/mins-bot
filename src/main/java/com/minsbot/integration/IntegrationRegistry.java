package com.minsbot.integration;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry of all supported integrations with their metadata.
 * Each entry defines how to talk to the service: base URL, auth type, docs link.
 * Credentials are stored separately via {@link IntegrationCredentialStore}.
 */
@Component
public class IntegrationRegistry {

    public enum AuthType {
        API_KEY,           // Single API key in header (e.g. Authorization: Bearer ... or X-Api-Key: ...)
        BEARER_TOKEN,      // OAuth-issued bearer token
        BASIC,             // Basic auth (username:password)
        OAUTH2,            // OAuth2 authorization code flow
        SDK,               // Native SDK required (AWS, GCP, etc.)
        BUILTIN            // Already implemented natively (Telegram, Slack, etc.)
    }

    public record Integration(
            String id,
            String name,
            String category,
            String baseUrl,
            AuthType authType,
            String authHeader,       // e.g. "Authorization: Bearer {token}" or "X-Api-Key: {key}"
            String docsUrl,
            String propertyPrefix    // e.g. "app.integrations.stripe"
    ) {}

    private final Map<String, Integration> integrations = new LinkedHashMap<>();

    public IntegrationRegistry() {
        // ─── Already built-in (messaging) ───
        reg("telegram", "Telegram", "Messaging", "https://api.telegram.org", AuthType.BUILTIN, null, "https://core.telegram.org/bots/api");
        reg("discord", "Discord", "Messaging", "https://discord.com/api/v10", AuthType.BUILTIN, null, "https://discord.com/developers/docs");
        reg("slack", "Slack", "Messaging", "https://slack.com/api", AuthType.BUILTIN, null, "https://api.slack.com/");
        reg("teams", "Microsoft Teams", "Messaging", "https://graph.microsoft.com/v1.0", AuthType.BUILTIN, null, "https://learn.microsoft.com/graph");
        reg("viber", "Viber", "Messaging", "https://chatapi.viber.com", AuthType.BUILTIN, null, "https://developers.viber.com/");
        reg("whatsapp", "WhatsApp", "Messaging", "https://graph.facebook.com", AuthType.BUILTIN, null, "https://developers.facebook.com/docs/whatsapp");
        reg("line", "LINE", "Messaging", "https://api.line.me", AuthType.BUILTIN, null, "https://developers.line.biz/");
        reg("wechat", "WeChat", "Messaging", "https://api.weixin.qq.com", AuthType.BUILTIN, null, "https://developers.weixin.qq.com/");
        reg("signal", "Signal", "Messaging", "http://localhost:8080", AuthType.BUILTIN, null, "https://github.com/bbernhard/signal-cli-rest-api");

        // ─── Google ecosystem (OAuth2) ───
        reg("google-ads", "Google Ads", "Google", "https://googleads.googleapis.com", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.google.com/google-ads");
        reg("google-search-console", "Search Console", "Google", "https://searchconsole.googleapis.com", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.google.com/webmaster-tools");
        reg("google-bigquery", "BigQuery", "Google", "https://bigquery.googleapis.com", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://cloud.google.com/bigquery/docs");

        // ─── Microsoft ecosystem (OAuth2) ───
        reg("outlook", "Outlook", "Microsoft 365", "https://graph.microsoft.com/v1.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://learn.microsoft.com/graph/api/resources/mail-api-overview");
        reg("onedrive", "OneDrive", "Microsoft 365", "https://graph.microsoft.com/v1.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://learn.microsoft.com/onedrive");
        reg("sharepoint", "SharePoint", "Microsoft 365", "https://graph.microsoft.com/v1.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://learn.microsoft.com/sharepoint");
        reg("azure", "Azure & Entra ID", "Microsoft 365", "https://management.azure.com", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://learn.microsoft.com/azure");

        // ─── Chat & meetings (OAuth2) ───
        reg("zoom", "Zoom", "Chat & meetings", "https://api.zoom.us/v2", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.zoom.us/");
        reg("webex", "Webex", "Chat & meetings", "https://webexapis.com/v1", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.webex.com/");
        reg("ringcentral", "RingCentral", "Chat & meetings", "https://platform.ringcentral.com/restapi/v1.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.ringcentral.com/");
        reg("mattermost", "Mattermost", "Chat & meetings", "https://your-mattermost.example.com/api/v4", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://api.mattermost.com/");
        reg("rocketchat", "Rocket.Chat", "Chat & meetings", "https://your-rocket.chat/api/v1", AuthType.API_KEY, "X-Auth-Token: {token}", "https://developer.rocket.chat/");

        // ─── Developer & issue tracking (OAuth2) ───
        reg("github", "GitHub", "Developer & issue tracking", "https://api.github.com", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://docs.github.com/rest");
        reg("gitlab", "GitLab", "Developer & issue tracking", "https://gitlab.com/api/v4", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://docs.gitlab.com/ee/api/");
        reg("bitbucket", "Bitbucket", "Developer & issue tracking", "https://api.bitbucket.org/2.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.atlassian.com/cloud/bitbucket/rest/");
        reg("jira", "Jira", "Developer & issue tracking", "https://your-domain.atlassian.net/rest/api/3", AuthType.BASIC, "Authorization: Basic {token}", "https://developer.atlassian.com/cloud/jira/platform/rest/");
        reg("confluence", "Confluence", "Developer & issue tracking", "https://your-domain.atlassian.net/wiki/api/v2", AuthType.BASIC, "Authorization: Basic {token}", "https://developer.atlassian.com/cloud/confluence/rest/");
        reg("linear", "Linear", "Developer & issue tracking", "https://api.linear.app/graphql", AuthType.API_KEY, "Authorization: {token}", "https://developers.linear.app/");
        reg("trello", "Trello", "Developer & issue tracking", "https://api.trello.com/1", AuthType.API_KEY, null, "https://developer.atlassian.com/cloud/trello/rest/");
        reg("asana", "Asana", "Developer & issue tracking", "https://app.asana.com/api/1.0", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developers.asana.com/docs");
        reg("monday", "Monday.com", "Developer & issue tracking", "https://api.monday.com/v2", AuthType.API_KEY, "Authorization: {token}", "https://developer.monday.com/");
        reg("clickup", "ClickUp", "Developer & issue tracking", "https://api.clickup.com/api/v2", AuthType.API_KEY, "Authorization: {token}", "https://clickup.com/api");
        reg("notion", "Notion", "Developer & issue tracking", "https://api.notion.com/v1", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developers.notion.com/");

        // ─── Docs & files (OAuth2) ───
        reg("dropbox", "Dropbox", "Docs & files", "https://api.dropboxapi.com/2", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://www.dropbox.com/developers");
        reg("box", "Box", "Docs & files", "https://api.box.com/2.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.box.com/");
        reg("airtable", "Airtable", "Docs & files", "https://api.airtable.com/v0", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://airtable.com/developers");
        reg("adobe-sign", "Adobe Acrobat Sign", "Docs & files", "https://api.na1.adobesign.com/api/rest/v6", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.adobe.com/document-services/");
        reg("docusign", "DocuSign", "Docs & files", "https://demo.docusign.net/restapi/v2.1", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.docusign.com/");

        // ─── CRM & marketing (OAuth2) ───
        reg("salesforce", "Salesforce", "CRM & marketing", "https://your-instance.my.salesforce.com/services/data/v60.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.salesforce.com/docs/");
        reg("hubspot", "HubSpot", "CRM & marketing", "https://api.hubapi.com", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developers.hubspot.com/");
        reg("intercom", "Intercom", "CRM & marketing", "https://api.intercom.io", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developers.intercom.com/");
        reg("mailchimp", "Mailchimp", "CRM & marketing", "https://us1.api.mailchimp.com/3.0", AuthType.API_KEY, "Authorization: Bearer {token}", "https://mailchimp.com/developer/");
        reg("segment", "Segment", "CRM & marketing", "https://api.segment.io/v1", AuthType.BASIC, "Authorization: Basic {token}", "https://segment.com/docs/api/");
        reg("customerio", "Customer.io", "CRM & marketing", "https://track.customer.io/api/v1", AuthType.BASIC, "Authorization: Basic {token}", "https://customer.io/docs/api/");

        // ─── Payments & commerce ───
        reg("stripe", "Stripe", "Payments & commerce", "https://api.stripe.com/v1", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://stripe.com/docs/api");
        reg("paypal", "PayPal", "Payments & commerce", "https://api-m.paypal.com/v2", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developer.paypal.com/");
        reg("shopify", "Shopify", "Payments & commerce", "https://your-store.myshopify.com/admin/api/2024-01", AuthType.API_KEY, "X-Shopify-Access-Token: {token}", "https://shopify.dev/");
        reg("squarespace", "Squarespace", "Payments & commerce", "https://api.squarespace.com/1.0", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developers.squarespace.com/");
        reg("woocommerce", "WooCommerce", "Payments & commerce", "https://your-store.com/wp-json/wc/v3", AuthType.BASIC, "Authorization: Basic {token}", "https://woocommerce.github.io/woocommerce-rest-api-docs/");
        reg("amazon-sp", "Amazon Selling Partner", "Payments & commerce", "https://sellingpartnerapi-na.amazon.com", AuthType.OAUTH2, "x-amz-access-token: {token}", "https://developer-docs.amazon.com/sp-api/");

        // ─── Social & ads (OAuth2) ───
        reg("linkedin", "LinkedIn", "Social & ads", "https://api.linkedin.com/v2", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.linkedin.com/");
        reg("twitter", "X (Twitter)", "Social & ads", "https://api.twitter.com/2", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.twitter.com/");
        reg("reddit", "Reddit", "Social & ads", "https://oauth.reddit.com", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://www.reddit.com/dev/api/");
        reg("meta", "Meta (Facebook / Instagram)", "Social & ads", "https://graph.facebook.com/v18.0", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.facebook.com/");
        reg("tiktok", "TikTok", "Social & ads", "https://open.tiktokapis.com/v2", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.tiktok.com/");
        reg("pinterest", "Pinterest", "Social & ads", "https://api.pinterest.com/v5", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developers.pinterest.com/");

        // ─── Design & media ───
        reg("figma", "Figma", "Design & media", "https://api.figma.com/v1", AuthType.API_KEY, "X-Figma-Token: {token}", "https://www.figma.com/developers/");
        reg("canva", "Canva", "Design & media", "https://api.canva.com/rest/v1", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://www.canva.dev/");
        reg("spotify", "Spotify", "Design & media", "https://api.spotify.com/v1", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.spotify.com/");
        reg("apple-music", "Apple (Music / Podcasts)", "Design & media", "https://api.music.apple.com/v1", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developer.apple.com/documentation/applemusicapi");
        reg("soundcloud", "SoundCloud", "Design & media", "https://api.soundcloud.com", AuthType.OAUTH2, "Authorization: OAuth {token}", "https://developers.soundcloud.com/");
        reg("vimeo", "Vimeo", "Design & media", "https://api.vimeo.com", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://developer.vimeo.com/");

        // ─── Cloud & deploy ───
        reg("aws", "AWS", "Cloud & deploy", "https://*.amazonaws.com", AuthType.SDK, null, "https://docs.aws.amazon.com/");
        reg("gcp", "Google Cloud Platform", "Cloud & deploy", "https://*.googleapis.com", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://cloud.google.com/docs");
        reg("cloudflare", "Cloudflare", "Cloud & deploy", "https://api.cloudflare.com/client/v4", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developers.cloudflare.com/api/");
        reg("vercel", "Vercel", "Cloud & deploy", "https://api.vercel.com", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://vercel.com/docs/rest-api");
        reg("netlify", "Netlify", "Cloud & deploy", "https://api.netlify.com/api/v1", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://docs.netlify.com/api/get-started/");
        reg("dockerhub", "Docker Hub", "Cloud & deploy", "https://hub.docker.com/v2", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://docs.docker.com/docker-hub/api/");

        // ─── Automation & scheduling ───
        reg("zapier", "Zapier", "Automation & scheduling", "https://hooks.zapier.com/hooks/catch", AuthType.API_KEY, null, "https://platform.zapier.com/");
        reg("make", "Make (Integromat)", "Automation & scheduling", "https://eu1.make.com/api/v2", AuthType.BEARER_TOKEN, "Authorization: Token {token}", "https://www.make.com/en/api-documentation");
        reg("ifttt", "IFTTT", "Automation & scheduling", "https://maker.ifttt.com/trigger", AuthType.API_KEY, null, "https://ifttt.com/maker_webhooks");
        reg("calendly", "Calendly", "Automation & scheduling", "https://api.calendly.com", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developer.calendly.com/");
        reg("calcom", "Cal.com", "Automation & scheduling", "https://api.cal.com/v1", AuthType.API_KEY, null, "https://cal.com/docs");
        reg("n8n", "n8n", "Automation & scheduling", "https://your-n8n.example.com/api/v1", AuthType.API_KEY, "X-N8N-API-KEY: {token}", "https://docs.n8n.io/api/");

        // ─── Security & identity ───
        reg("okta", "Okta", "Security & identity", "https://your-org.okta.com/api/v1", AuthType.API_KEY, "Authorization: SSWS {token}", "https://developer.okta.com/");
        reg("onepassword", "1Password", "Security & identity", "https://your-account.1password.com/api/v1", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://developer.1password.com/");
        reg("auth0", "Auth0", "Security & identity", "https://your-tenant.auth0.com/api/v2", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://auth0.com/docs/api");
        reg("duo", "Duo / Cisco Secure", "Security & identity", "https://api-xxx.duosecurity.com/admin/v1", AuthType.BASIC, "Authorization: Basic {token}", "https://duo.com/docs/adminapi");

        // ─── Data & analytics ───
        reg("snowflake", "Snowflake", "Data & analytics", "https://your-account.snowflakecomputing.com/api/v2", AuthType.OAUTH2, "Authorization: Bearer {token}", "https://docs.snowflake.com/");
        reg("databricks", "Databricks", "Data & analytics", "https://your-workspace.cloud.databricks.com/api/2.0", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://docs.databricks.com/api/");
        reg("mixpanel", "Mixpanel", "Data & analytics", "https://api.mixpanel.com", AuthType.BASIC, "Authorization: Basic {token}", "https://developer.mixpanel.com/");
        reg("amplitude", "Amplitude", "Data & analytics", "https://amplitude.com/api/2", AuthType.BASIC, "Authorization: Basic {token}", "https://developers.amplitude.com/");
        reg("llm-providers", "OpenAI / Anthropic / etc.", "Data & analytics", "https://api.openai.com/v1", AuthType.BEARER_TOKEN, "Authorization: Bearer {token}", "https://platform.openai.com/docs");
    }

    private void reg(String id, String name, String category, String baseUrl, AuthType authType, String authHeader, String docsUrl) {
        integrations.put(id, new Integration(id, name, category, baseUrl, authType, authHeader, docsUrl, "app.integrations." + id));
    }

    public Optional<Integration> get(String id) { return Optional.ofNullable(integrations.get(id)); }
    public Collection<Integration> all() { return Collections.unmodifiableCollection(integrations.values()); }
    public List<Integration> byCategory(String category) {
        return integrations.values().stream().filter(i -> i.category().equalsIgnoreCase(category)).toList();
    }
    public Set<String> categories() {
        return integrations.values().stream().map(Integration::category).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
    public int size() { return integrations.size(); }
}
