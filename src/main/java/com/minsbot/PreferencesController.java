package com.minsbot;

import com.minsbot.agent.BargeInService;
import com.minsbot.agent.AgentLoopService;
import com.minsbot.agent.WorkingSoundService;
import com.minsbot.agent.tools.LocalImageTools;
import com.minsbot.agent.tools.LocalModelTools;
import com.minsbot.agent.tools.ToolClassifierService;
import com.minsbot.cost.TokenUsageService;
import com.minsbot.release.UpdateCheckService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single REST surface for the Preferences tab — autonomous mode, barge-in tuning,
 * cost budgets, window behavior, local-model URLs, update check, agent loop,
 * tool classifier model.
 */
@RestController
@RequestMapping("/api/prefs")
public class PreferencesController {

    private final ChatService chatService;
    private final BargeInService bargeIn;
    private final TokenUsageService cost;
    private final WorkingSoundService sound;
    private final AgentLoopService agentLoop;
    private final UpdateCheckService updateCheck;
    private final ToolClassifierService toolClassifier;
    private final LocalModelTools localModel;
    private final LocalImageTools localImage;

    public PreferencesController(ChatService chatService,
                                  BargeInService bargeIn,
                                  TokenUsageService cost,
                                  WorkingSoundService sound,
                                  AgentLoopService agentLoop,
                                  UpdateCheckService updateCheck,
                                  ToolClassifierService toolClassifier,
                                  LocalModelTools localModel,
                                  LocalImageTools localImage) {
        this.chatService = chatService;
        this.bargeIn = bargeIn;
        this.cost = cost;
        this.sound = sound;
        this.agentLoop = agentLoop;
        this.updateCheck = updateCheck;
        this.toolClassifier = toolClassifier;
        this.localModel = localModel;
        this.localImage = localImage;
    }

    /** Full snapshot of every preference value. */
    @GetMapping(value = "/all", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> all() {
        Map<String, Object> autonomous = new LinkedHashMap<>();
        autonomous.put("enabled", chatService.isAutonomousEnabled());
        autonomous.put("idleTimeoutSeconds", chatService.getAutonomousIdleTimeoutSeconds());
        autonomous.put("pauseBetweenStepsMs", chatService.getAutonomousPauseBetweenStepsMs());

        Map<String, Object> bi = new LinkedHashMap<>();
        bi.put("enabled", bargeIn.isEnabled());
        bi.put("thresholdMultiplier", bargeIn.getThresholdMultiplier());
        bi.put("minRms", bargeIn.getMinRms());
        bi.put("consecutiveFrames", bargeIn.getConsecutiveFrames());
        bi.put("warmupMs", bargeIn.getWarmupMs());

        Map<String, Object> co = new LinkedHashMap<>();
        co.put("dailyBudgetUsd", cost.getDailyBudgetUsd());
        co.put("alertThresholdFraction", cost.getAlertThresholdFraction());
        co.put("capMode", cost.getCapMode());
        co.put("spentToday", cost.spentToday());
        co.put("overBudget", cost.isOverBudget());

        Map<String, Object> win = new LinkedHashMap<>();
        win.put("alwaysOnTop", FloatingAppLauncher.isAlwaysOnTop());
        win.put("soundEnabled", sound.isSoundEnabled());
        win.put("volume", sound.getVolume());

        Map<String, Object> misc = new LinkedHashMap<>();
        misc.put("agentMaxSteps", agentLoop.getMaxSteps());
        misc.put("updateCheckEnabled", updateCheck.isEnabled());
        misc.put("toolClassifierModel", toolClassifier.getModel());
        misc.put("ollamaUrl", localModel.getOllamaApiUrl());
        misc.put("comfyUrl", localImage.getComfyApiUrl());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("autonomous", autonomous);
        out.put("bargein", bi);
        out.put("cost", co);
        out.put("window", win);
        out.put("misc", misc);
        return out;
    }

    // ── Autonomous ──

    @PostMapping(value = "/autonomous", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setAutonomous(@RequestBody Map<String, Object> body) {
        Object en = body.get("enabled");
        if (en != null) chatService.setAutonomousEnabled(Boolean.parseBoolean(en.toString()));
        Object it = body.get("idleTimeoutSeconds");
        if (it != null) { try { chatService.setAutonomousIdleTimeoutSeconds(Integer.parseInt(it.toString())); } catch (NumberFormatException ignored) {} }
        Object pm = body.get("pauseBetweenStepsMs");
        if (pm != null) { try { chatService.setAutonomousPauseBetweenStepsMs(Integer.parseInt(pm.toString())); } catch (NumberFormatException ignored) {} }
        return all();
    }

    // ── Barge-in ──

    @PostMapping(value = "/bargein", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setBargein(@RequestBody Map<String, Object> body) {
        Object en = body.get("enabled");
        if (en != null) bargeIn.setEnabled(Boolean.parseBoolean(en.toString()));
        Object tm = body.get("thresholdMultiplier");
        if (tm != null) { try { bargeIn.setThresholdMultiplier(Double.parseDouble(tm.toString())); } catch (NumberFormatException ignored) {} }
        Object mr = body.get("minRms");
        if (mr != null) { try { bargeIn.setMinRms(Double.parseDouble(mr.toString())); } catch (NumberFormatException ignored) {} }
        Object cf = body.get("consecutiveFrames");
        if (cf != null) { try { bargeIn.setConsecutiveFrames(Integer.parseInt(cf.toString())); } catch (NumberFormatException ignored) {} }
        Object wm = body.get("warmupMs");
        if (wm != null) { try { bargeIn.setWarmupMs(Integer.parseInt(wm.toString())); } catch (NumberFormatException ignored) {} }
        return all();
    }

    // ── Cost ──

    @PostMapping(value = "/cost", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setCost(@RequestBody Map<String, Object> body) {
        Object db = body.get("dailyBudgetUsd");
        if (db != null) { try { cost.setDailyBudgetUsd(Double.parseDouble(db.toString())); } catch (NumberFormatException ignored) {} }
        Object at = body.get("alertThresholdFraction");
        if (at != null) { try { cost.setAlertThresholdFraction(Double.parseDouble(at.toString())); } catch (NumberFormatException ignored) {} }
        Object cm = body.get("capMode");
        if (cm != null) cost.setCapMode(cm.toString());
        return all();
    }

    // ── Window ──

    @PostMapping(value = "/window", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setWindow(@RequestBody Map<String, Object> body) {
        Object aot = body.get("alwaysOnTop");
        if (aot != null) FloatingAppLauncher.setAlwaysOnTop(Boolean.parseBoolean(aot.toString()));
        Object se = body.get("soundEnabled");
        if (se != null) sound.setSoundEnabled(Boolean.parseBoolean(se.toString()));
        Object v = body.get("volume");
        if (v != null) { try { sound.setVolume(Float.parseFloat(v.toString())); } catch (NumberFormatException ignored) {} }
        return all();
    }

    // ── Misc (agent max steps, update check, classifier model, local URLs) ──

    @PostMapping(value = "/misc", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setMisc(@RequestBody Map<String, Object> body) {
        Object ms = body.get("agentMaxSteps");
        if (ms != null) { try { agentLoop.setMaxSteps(Integer.parseInt(ms.toString())); } catch (NumberFormatException ignored) {} }
        Object uc = body.get("updateCheckEnabled");
        if (uc != null) updateCheck.setEnabled(Boolean.parseBoolean(uc.toString()));
        Object tm = body.get("toolClassifierModel");
        if (tm != null) toolClassifier.setModel(tm.toString());
        Object ou = body.get("ollamaUrl");
        if (ou != null) localModel.setOllamaApiUrl(ou.toString());
        Object cu = body.get("comfyUrl");
        if (cu != null) localImage.setComfyApiUrl(cu.toString());
        return all();
    }
}
