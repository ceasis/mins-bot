package com.minsbot.agent.tools;

import com.minsbot.agent.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Alt+Tab app switcher with vision-based target detection.
 * Uses java.awt.Robot for all key events and screenshots.
 *
 * Phase 1: Hold ALT, TAB through windows, screenshot each, release ALT.
 * Phase 2: Analyze screenshots with Gemini Vision to find the target.
 * Phase 3: Alt+Tab exactly N times to reach the target.
 */
@Component
public class AppSwitchTools {

    private static final Logger log = LoggerFactory.getLogger(AppSwitchTools.class);
    private static final int MAX_TABS = 10;

    private final VisionService visionService;
    private final ToolExecutionNotifier notifier;

    public AppSwitchTools(VisionService visionService, ToolExecutionNotifier notifier) {
        this.visionService = visionService;
        this.notifier = notifier;
    }

    @Tool(description = "Switch to a specific application window using Alt+Tab. " +
            "Cycles through open windows and uses vision to find the target app. " +
            "Use this when you need to switch to a specific app like Chrome, VS Code, Excel, etc.")
    public String switchToApp(
            @ToolParam(description = "The name or description of the target app to switch to, " +
                    "e.g. 'Google Chrome', 'Visual Studio Code', 'File Explorer', 'Discord'")
            String targetApp) {

        notifier.notify("Switching to " + targetApp + "...");

        if (!visionService.isAvailable()) {
            return "Vision service is not available — cannot identify apps in Alt+Tab switcher. " +
                    "Check that app.openai.api-key is configured.";
        }

        Robot robot;
        try {
            robot = new Robot();
            robot.setAutoDelay(10);
        } catch (Exception e) {
            return "Failed to create Robot: " + e.getMessage();
        }

        // ═══ Phase 1: Hold ALT, TAB through windows, screenshot each ═══
        List<Path> screenshots = new ArrayList<>();
        try {
            log.info("[AppSwitch] Phase 1 — capturing Alt+Tab screenshots for '{}'", targetApp);

            // Hold ALT
            robot.keyPress(KeyEvent.VK_ALT);
            robot.delay(400);

            for (int i = 0; i < MAX_TABS; i++) {
                // Press and release TAB (ALT is still held)
                robot.keyPress(KeyEvent.VK_TAB);
                robot.delay(100);
                robot.keyRelease(KeyEvent.VK_TAB);
                robot.delay(600); // let the switcher UI settle

                // Screenshot while ALT is still held (switcher overlay visible)
                BufferedImage img = captureScreen(robot);
                Path file = Files.createTempFile("alttab_" + i + "_", ".png");
                ImageIO.write(img, "png", file.toFile());
                screenshots.add(file);
                log.info("[AppSwitch] Captured position {} -> {}", i + 1, file.getFileName());
            }

            // Release ALT — dismisses switcher, returns to original window
            robot.keyRelease(KeyEvent.VK_ALT);
            robot.delay(400);
            log.info("[AppSwitch] Phase 1 done — {} screenshots", screenshots.size());

        } catch (Exception e) {
            try { robot.keyRelease(KeyEvent.VK_ALT); } catch (Exception ignored) {}
            cleanupFiles(screenshots);
            log.error("[AppSwitch] Phase 1 failed: {}", e.getMessage(), e);
            return "App switch failed during capture: " + e.getMessage();
        }

        if (screenshots.isEmpty()) {
            return "Failed to capture any Alt+Tab screenshots.";
        }

        // ═══ Phase 2: Analyze screenshots to find target app position ═══
        int targetPosition = -1;
        try {
            log.info("[AppSwitch] Phase 2 — analyzing {} screenshots to find '{}'",
                    screenshots.size(), targetApp);

            for (int i = 0; i < screenshots.size(); i++) {
                Path file = screenshots.get(i);
                String prompt = String.format(
                        "You are looking at a Windows Alt+Tab task switcher overlay screenshot. " +
                        "The currently SELECTED/HIGHLIGHTED window thumbnail has a bright border or is visually emphasized. " +
                        "Is the currently selected/highlighted window '%s'? " +
                        "Look at the window title, icon, and thumbnail of the SELECTED item only. " +
                        "Respond with EXACTLY 'YES' or 'NO' on the first line. " +
                        "On the second line, briefly state what app IS currently selected.", targetApp);

                String response = visionService.analyzeWithPrompt(file, prompt);
                if (response == null || response.isBlank()) {
                    log.warn("[AppSwitch] Position {} — no vision response", i + 1);
                    continue;
                }

                String firstLine = response.split("\\r?\\n")[0].trim().toUpperCase();
                log.info("[AppSwitch] Position {} — vision says: {}", i + 1, response.trim());

                if (firstLine.startsWith("YES")) {
                    targetPosition = i + 1;
                    log.info("[AppSwitch] Target '{}' found at position {}", targetApp, targetPosition);
                    break;
                }
            }
        } finally {
            cleanupFiles(screenshots);
        }

        if (targetPosition < 0) {
            return "Could not find '" + targetApp + "' in the Alt+Tab switcher after checking " +
                    screenshots.size() + " windows.";
        }

        // ═══ Phase 3: Alt+Tab N times to reach the target ═══
        try {
            log.info("[AppSwitch] Phase 3 — Alt+Tab {} times to reach '{}'", targetPosition, targetApp);

            robot.keyPress(KeyEvent.VK_ALT);
            robot.delay(300);

            for (int i = 0; i < targetPosition; i++) {
                robot.keyPress(KeyEvent.VK_TAB);
                robot.delay(100);
                robot.keyRelease(KeyEvent.VK_TAB);
                robot.delay(200);
            }

            // Release ALT to confirm selection
            robot.keyRelease(KeyEvent.VK_ALT);
            robot.delay(500);

            log.info("[AppSwitch] Switched to '{}' at position {}", targetApp, targetPosition);
            return "Switched to " + targetApp + " (found at position " + targetPosition + ")";

        } catch (Exception e) {
            try { robot.keyRelease(KeyEvent.VK_ALT); } catch (Exception ignored) {}
            log.error("[AppSwitch] Phase 3 failed: {}", e.getMessage(), e);
            return "Found " + targetApp + " at position " + targetPosition +
                    " but failed to switch: " + e.getMessage();
        }
    }

    private BufferedImage captureScreen(Robot robot) {
        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage img = robot.createScreenCapture(screenRect);
        com.minsbot.agent.SystemControlService.drawCursorOnImage(img, 0, 0);
        return img;
    }

    private void cleanupFiles(List<Path> files) {
        for (Path f : files) {
            try { Files.deleteIfExists(f); } catch (Exception ignored) {}
        }
    }
}
