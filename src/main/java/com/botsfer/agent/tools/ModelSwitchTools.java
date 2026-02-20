package com.botsfer.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI-callable tools for switching the AI model at runtime.
 * Modifies the Spring AI property so the next ChatClient call uses the new model.
 */
@Component
public class ModelSwitchTools {

    private static final Logger log = LoggerFactory.getLogger(ModelSwitchTools.class);

    private final ToolExecutionNotifier notifier;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String currentModel;

    private final org.springframework.core.env.ConfigurableEnvironment environment;

    public ModelSwitchTools(ToolExecutionNotifier notifier,
                            org.springframework.core.env.ConfigurableEnvironment environment) {
        this.notifier = notifier;
        this.environment = environment;
    }

    @Tool(description = "Get the currently active AI model name.")
    public String getCurrentModel() {
        notifier.notify("Checking current model");
        return "Current model: " + currentModel;
    }

    @Tool(description = "Switch the AI model at runtime. Supported models include: " +
            "gpt-4o-mini, gpt-4o, gpt-4-turbo, gpt-3.5-turbo, or any OpenAI-compatible model name. " +
            "The change takes effect on the next message.")
    public String switchModel(
            @ToolParam(description = "Model name to switch to, e.g. 'gpt-4o', 'gpt-4o-mini'") String modelName) {
        notifier.notify("Switching model to: " + modelName);
        try {
            if (modelName == null || modelName.isBlank()) {
                return "Model name cannot be empty.";
            }
            String oldModel = currentModel;
            currentModel = modelName.trim();

            // Update the Spring property so ChatClient picks it up
            System.setProperty("spring.ai.openai.chat.options.model", currentModel);

            log.info("[ModelSwitch] Changed model: {} → {}", oldModel, currentModel);
            return "Model switched from " + oldModel + " to " + currentModel
                    + ". The change will take effect on the next message.";
        } catch (Exception e) {
            log.error("[ModelSwitch] Failed: {}", e.getMessage());
            return "Failed to switch model: " + e.getMessage();
        }
    }

    @Tool(description = "List available AI models that can be used.")
    public String listAvailableModels() {
        notifier.notify("Listing models");
        return """
                Available OpenAI models:
                1. gpt-4o-mini — Fast, affordable, good for most tasks (default)
                2. gpt-4o — Most capable, best for complex reasoning
                3. gpt-4-turbo — Previous generation, 128k context
                4. gpt-3.5-turbo — Fastest, cheapest, less capable
                5. o1-mini — Reasoning model, good for math/code
                6. o1-preview — Full reasoning model

                Current model: """ + currentModel + """

                You can also use any OpenAI-compatible model name (e.g. from a local server).""";
    }
}
