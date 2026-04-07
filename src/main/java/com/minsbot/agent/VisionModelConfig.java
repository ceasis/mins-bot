package com.minsbot.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for vision model names used across the system.
 * All vision-related services should read from here instead of hardcoding model strings.
 *
 * <p>Config keys in application.properties:
 * <ul>
 *   <li>{@code app.vision.primary-model} — main vision model (default: gpt-5.4)</li>
 *   <li>{@code app.vision.fast-model} — lighter/cheaper model for quick checks (default: gpt-4o-mini)</li>
 *   <li>{@code app.vision.verify-model} — model used for coordinate verification (default: gpt-5.4)</li>
 * </ul>
 */
@Component
public class VisionModelConfig {

    @Value("${app.vision.primary-model:gpt-5.4}")
    private String primaryModel;

    @Value("${app.vision.fast-model:gpt-4o-mini}")
    private String fastModel;

    @Value("${app.vision.verify-model:gpt-5.4}")
    private String verifyModel;

    /** Primary vision model — used for screen analysis, element detection, proactive mode. */
    public String getPrimaryModel() { return primaryModel; }

    /** Fast/cheap vision model — used for quick checks, OCR fallback, lightweight analysis. */
    public String getFastModel() { return fastModel; }

    /** Verification model — used for confirming click coordinates. */
    public String getVerifyModel() { return verifyModel; }

    /** Config key for primary model in engine priority list (e.g. "gpt5.4"). */
    public String getPrimaryEngineKey() {
        return primaryModel.replace("-", "").replace(".", ".");
    }

    /** Config key for fast model in engine priority list (e.g. "gpt4o-mini"). */
    public String getFastEngineKey() {
        return fastModel.replace(".", "");
    }
}
