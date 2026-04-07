package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Video creation tools using Remotion (React-based programmatic video framework).
 * Manages a Remotion project at ~/mins_bot_data/remotion/ and renders videos via CLI.
 */
@Component
public class RemotionVideoTools {

    private static final Logger log = LoggerFactory.getLogger(RemotionVideoTools.class);
    private static final Path REMOTION_DIR = Paths.get(System.getProperty("user.home"), "mins_bot_data", "remotion");
    private static final Path COMPOSITIONS_DIR = REMOTION_DIR.resolve("src").resolve("compositions");
    private static final Path OUTPUT_DIR = Paths.get(System.getProperty("user.home"), "mins_bot_data", "videos");

    private final ToolExecutionNotifier notifier;
    private final SystemControlService systemControl;

    public RemotionVideoTools(SystemControlService systemControl, ToolExecutionNotifier notifier) {
        this.systemControl = systemControl;
        this.notifier = notifier;
    }

    @Tool(description = "Initialize/setup the Remotion video project. Run this once before creating videos. "
            + "Requires Node.js 18+ to be installed. Creates the project at ~/mins_bot_data/remotion/")
    public String setupRemotion() {
        notifier.notify("Setting up Remotion video project...");

        // Check Node.js
        String nodeVersion = systemControl.runCmd("node --version");
        if (nodeVersion.contains("error") || nodeVersion.contains("not recognized")) {
            return "FAILED: Node.js not found. Install Node.js 18+ first (https://nodejs.org).";
        }
        log.info("[Remotion] Node.js version: {}", nodeVersion.trim());

        // Check if already set up
        Path packageJson = REMOTION_DIR.resolve("package.json");
        Path nodeModules = REMOTION_DIR.resolve("node_modules");
        if (Files.exists(packageJson) && Files.isDirectory(nodeModules)) {
            return "Remotion project already set up at " + REMOTION_DIR
                    + "\nNode.js: " + nodeVersion.trim()
                    + "\nReady to create and render videos.";
        }

        try {
            // Create directory structure
            Files.createDirectories(COMPOSITIONS_DIR);
            Files.createDirectories(REMOTION_DIR.resolve("public"));
            Files.createDirectories(OUTPUT_DIR);

            // Write package.json
            Files.writeString(packageJson, PACKAGE_JSON);

            // Write tsconfig.json
            Files.writeString(REMOTION_DIR.resolve("tsconfig.json"), TSCONFIG_JSON);

            // Write remotion.config.ts
            Files.writeString(REMOTION_DIR.resolve("remotion.config.ts"), REMOTION_CONFIG);

            // Write src/index.ts
            Files.writeString(REMOTION_DIR.resolve("src").resolve("index.ts"), INDEX_TS);

            // Write src/Root.tsx (empty compositions initially)
            writeRootTsx(List.of());

            log.info("[Remotion] Project scaffolded at {}", REMOTION_DIR);

        } catch (IOException e) {
            log.error("[Remotion] Failed to scaffold project", e);
            return "FAILED: Could not create project files: " + e.getMessage();
        }

        // Run npm install
        notifier.notify("Installing Remotion dependencies (this may take a minute)...");
        String installResult = systemControl.runCmd(
                "cd /d \"" + REMOTION_DIR + "\" && npm install");
        log.info("[Remotion] npm install output: {}", installResult);

        if (!Files.isDirectory(nodeModules)) {
            return "FAILED: npm install did not create node_modules. Output:\n" + installResult;
        }

        return "Remotion project set up successfully at " + REMOTION_DIR
                + "\nNode.js: " + nodeVersion.trim()
                + "\nDependencies installed. Ready to create and render videos.";
    }

    @Tool(description = "Create a video composition from React/Remotion code. This writes a new composition file "
            + "that can then be rendered. The code should be a valid React component using Remotion APIs "
            + "(useCurrentFrame, useVideoConfig, AbsoluteFill, Sequence, spring, interpolate, etc). "
            + "Example: createComposition('MyVideo', code, 30, 150) creates a 5-second video at 30fps.")
    public String createComposition(
            @ToolParam(description = "Name for this composition (PascalCase, e.g. 'IntroVideo')") String name,
            @ToolParam(description = "React/TSX component code for the video. Must export default a React component. "
                    + "Can use Remotion APIs: useCurrentFrame(), useVideoConfig(), AbsoluteFill, Sequence, "
                    + "spring(), interpolate(), Img, Audio, Video, Series, etc.") String code,
            @ToolParam(description = "Frames per second (typically 30 or 60)") int fps,
            @ToolParam(description = "Total duration in frames (e.g. 150 for 5 seconds at 30fps)") int durationInFrames) {
        notifier.notify("Creating composition: " + name + "...");

        if (!name.matches("[A-Z][A-Za-z0-9]+")) {
            return "FAILED: Name must be PascalCase (start with uppercase, alphanumeric only). Example: 'IntroVideo'";
        }
        if (fps < 1 || fps > 120) {
            return "FAILED: FPS must be between 1 and 120.";
        }
        if (durationInFrames < 1 || durationInFrames > 36000) {
            return "FAILED: Duration must be between 1 and 36000 frames.";
        }
        if (!Files.isDirectory(REMOTION_DIR)) {
            return "FAILED: Remotion project not set up. Run setupRemotion() first.";
        }

        try {
            Files.createDirectories(COMPOSITIONS_DIR);
            Path compFile = COMPOSITIONS_DIR.resolve(name + ".tsx");
            Files.writeString(compFile, code);
            log.info("[Remotion] Wrote composition: {}", compFile);

            // Update Root.tsx to register this composition
            updateRootTsx();

            double seconds = (double) durationInFrames / fps;
            return "Composition '" + name + "' created (" + fps + "fps, "
                    + durationInFrames + " frames, " + String.format("%.1f", seconds) + "s).\n"
                    + "File: " + compFile + "\n"
                    + "Render it with: renderVideo('" + name + "', 'output.mp4')";

        } catch (IOException e) {
            log.error("[Remotion] Failed to create composition", e);
            return "FAILED: " + e.getMessage();
        }
    }

    @Tool(description = "Render a Remotion composition to an MP4 video file. "
            + "The composition must have been created first with createComposition. "
            + "Returns the path to the rendered video.")
    public String renderVideo(
            @ToolParam(description = "Name of the composition to render (must match a created composition)") String compositionName,
            @ToolParam(description = "Output filename (e.g. 'my-video.mp4'). Saved to ~/mins_bot_data/videos/") String outputFilename) {
        notifier.notify("Rendering video: " + compositionName + "...");

        if (!Files.isDirectory(REMOTION_DIR.resolve("node_modules"))) {
            return "FAILED: Remotion project not set up or dependencies missing. Run setupRemotion() first.";
        }

        Path compFile = COMPOSITIONS_DIR.resolve(compositionName + ".tsx");
        if (!Files.exists(compFile)) {
            return "FAILED: Composition '" + compositionName + "' not found. Create it first with createComposition().";
        }

        try {
            Files.createDirectories(OUTPUT_DIR);
        } catch (IOException e) {
            return "FAILED: Cannot create output directory: " + e.getMessage();
        }

        if (!outputFilename.endsWith(".mp4")) {
            outputFilename = outputFilename + ".mp4";
        }
        Path outputPath = OUTPUT_DIR.resolve(outputFilename);

        String cmd = "cd /d \"" + REMOTION_DIR + "\" && npx remotion render src/index.ts "
                + compositionName + " \"" + outputPath + "\"";
        log.info("[Remotion] Render command: {}", cmd);

        String result = systemControl.runCmd(cmd);
        log.info("[Remotion] Render output: {}", result);

        if (Files.exists(outputPath)) {
            try {
                long sizeKb = Files.size(outputPath) / 1024;
                return "Video rendered successfully!\nFile: " + outputPath
                        + "\nSize: " + sizeKb + " KB";
            } catch (IOException e) {
                return "Video rendered: " + outputPath;
            }
        }

        return "Rendering may have failed. Output:\n" + result;
    }

    @Tool(description = "List all available video compositions that have been created.")
    public String listCompositions() {
        notifier.notify("Listing compositions...");

        if (!Files.isDirectory(COMPOSITIONS_DIR)) {
            return "No compositions directory found. Run setupRemotion() first.";
        }

        try (Stream<Path> files = Files.list(COMPOSITIONS_DIR)) {
            List<String> entries = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".tsx"))
                    .sorted()
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String compName = fileName.replace(".tsx", "");
                        try {
                            long size = Files.size(p);
                            entries.add("  - " + compName + " (" + size + " bytes)");
                        } catch (IOException e) {
                            entries.add("  - " + compName);
                        }
                    });

            if (entries.isEmpty()) {
                return "No compositions found. Create one with createComposition().";
            }
            return "Compositions (" + entries.size() + "):\n" + String.join("\n", entries);

        } catch (IOException e) {
            return "Error listing compositions: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a video composition.")
    public String deleteComposition(
            @ToolParam(description = "Name of the composition to delete") String name) {
        notifier.notify("Deleting composition: " + name + "...");

        Path compFile = COMPOSITIONS_DIR.resolve(name + ".tsx");
        if (!Files.exists(compFile)) {
            return "Composition '" + name + "' not found.";
        }

        try {
            Files.delete(compFile);
            updateRootTsx();
            return "Composition '" + name + "' deleted.";
        } catch (IOException e) {
            return "FAILED: " + e.getMessage();
        }
    }

    @Tool(description = "List all rendered videos in the output directory.")
    public String listRenderedVideos() {
        notifier.notify("Listing rendered videos...");

        if (!Files.isDirectory(OUTPUT_DIR)) {
            return "No videos directory found. Render a video first.";
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());

        try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
            List<String> entries = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".mp4"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            long sizeKb = attrs.size() / 1024;
                            String date = dtf.format(attrs.lastModifiedTime().toInstant());
                            entries.add("  - " + p.getFileName() + " (" + sizeKb + " KB, " + date + ")");
                        } catch (IOException e) {
                            entries.add("  - " + p.getFileName());
                        }
                    });

            if (entries.isEmpty()) {
                return "No rendered videos found.";
            }
            return "Rendered videos (" + entries.size() + "):\n" + String.join("\n", entries);

        } catch (IOException e) {
            return "Error listing videos: " + e.getMessage();
        }
    }

    @Tool(description = "Create a quick text-based video with animated text on a colored background. "
            + "No coding needed - just provide the text and styling options.")
    public String createQuickTextVideo(
            @ToolParam(description = "The text to display in the video") String text,
            @ToolParam(description = "Background color (hex, e.g. '#1a1a2e')") String backgroundColor,
            @ToolParam(description = "Text color (hex, e.g. '#ffffff')") String textColor,
            @ToolParam(description = "Duration in seconds") int durationSeconds,
            @ToolParam(description = "Output filename (e.g. 'title.mp4')") String outputFilename) {
        notifier.notify("Creating quick text video...");

        if (!Files.isDirectory(REMOTION_DIR.resolve("node_modules"))) {
            return "FAILED: Remotion project not set up. Run setupRemotion() first.";
        }
        if (durationSeconds < 1 || durationSeconds > 120) {
            return "FAILED: Duration must be between 1 and 120 seconds.";
        }

        int fps = 30;
        int frames = fps * durationSeconds;
        String compName = "QuickText" + System.currentTimeMillis();

        // Escape text for JSX
        String escapedText = text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("`", "\\`")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        String code = "import React from 'react';\n"
                + "import { AbsoluteFill, useCurrentFrame, interpolate, spring, useVideoConfig } from 'remotion';\n"
                + "\n"
                + "const " + compName + ": React.FC = () => {\n"
                + "  const frame = useCurrentFrame();\n"
                + "  const { fps } = useVideoConfig();\n"
                + "  const scale = spring({ frame, fps, config: { damping: 12 } });\n"
                + "  const opacity = interpolate(frame, [0, 20], [0, 1], { extrapolateRight: 'clamp' });\n"
                + "  return (\n"
                + "    <AbsoluteFill style={{ backgroundColor: '" + backgroundColor + "', justifyContent: 'center', alignItems: 'center' }}>\n"
                + "      <h1 style={{\n"
                + "        color: '" + textColor + "',\n"
                + "        fontSize: 80,\n"
                + "        transform: `scale(${scale})`,\n"
                + "        opacity,\n"
                + "        fontFamily: 'Arial, sans-serif',\n"
                + "        textAlign: 'center',\n"
                + "        padding: '0 40px',\n"
                + "        maxWidth: '90%',\n"
                + "      }}>\n"
                + "        " + escapedText + "\n"
                + "      </h1>\n"
                + "    </AbsoluteFill>\n"
                + "  );\n"
                + "};\n"
                + "\n"
                + "export default " + compName + ";\n"
                + "export const fps = " + fps + ";\n"
                + "export const durationInFrames = " + frames + ";\n";

        String createResult = createComposition(compName, code, fps, frames);
        if (createResult.startsWith("FAILED")) {
            return createResult;
        }

        return renderVideo(compName, outputFilename);
    }

    @Tool(description = "Create a slideshow video from a list of image file paths. "
            + "Each image is shown for the specified duration with crossfade transitions.")
    public String createSlideshowVideo(
            @ToolParam(description = "Comma-separated list of image file paths") String imagePaths,
            @ToolParam(description = "Duration per slide in seconds") int secondsPerSlide,
            @ToolParam(description = "Output filename (e.g. 'slideshow.mp4')") String outputFilename) {
        notifier.notify("Creating slideshow video...");

        if (!Files.isDirectory(REMOTION_DIR.resolve("node_modules"))) {
            return "FAILED: Remotion project not set up. Run setupRemotion() first.";
        }
        if (secondsPerSlide < 1 || secondsPerSlide > 30) {
            return "FAILED: Duration per slide must be between 1 and 30 seconds.";
        }

        String[] paths = imagePaths.split(",");
        List<String> validImages = new ArrayList<>();
        Path publicDir = REMOTION_DIR.resolve("public");

        try {
            Files.createDirectories(publicDir);
        } catch (IOException e) {
            return "FAILED: Cannot create public directory: " + e.getMessage();
        }

        // Copy images to public/ folder
        for (int i = 0; i < paths.length; i++) {
            Path src = Paths.get(paths[i].trim()).toAbsolutePath();
            if (!Files.exists(src)) {
                return "FAILED: Image not found: " + src;
            }
            String ext = getExtension(src.getFileName().toString());
            String destName = "slide_" + i + "." + ext;
            try {
                Files.copy(src, publicDir.resolve(destName), StandardCopyOption.REPLACE_EXISTING);
                validImages.add(destName);
            } catch (IOException e) {
                return "FAILED: Cannot copy image " + src + ": " + e.getMessage();
            }
        }

        int fps = 30;
        int framesPerSlide = fps * secondsPerSlide;
        int totalFrames = framesPerSlide * validImages.size();
        String compName = "Slideshow" + System.currentTimeMillis();

        // Build the slides array for the component
        StringBuilder slidesArray = new StringBuilder("const slides = [\n");
        for (String img : validImages) {
            slidesArray.append("    '").append(img).append("',\n");
        }
        slidesArray.append("  ];\n");

        String code = "import React from 'react';\n"
                + "import { AbsoluteFill, useCurrentFrame, interpolate, Img, staticFile } from 'remotion';\n"
                + "\n"
                + "const " + compName + ": React.FC = () => {\n"
                + "  const frame = useCurrentFrame();\n"
                + "  " + slidesArray
                + "  const framesPerSlide = " + framesPerSlide + ";\n"
                + "  const currentSlideIndex = Math.min(Math.floor(frame / framesPerSlide), slides.length - 1);\n"
                + "  const slideFrame = frame - currentSlideIndex * framesPerSlide;\n"
                + "  const opacity = interpolate(slideFrame, [0, 15, framesPerSlide - 15, framesPerSlide], [0, 1, 1, 0], { extrapolateRight: 'clamp', extrapolateLeft: 'clamp' });\n"
                + "\n"
                + "  return (\n"
                + "    <AbsoluteFill style={{ backgroundColor: '#000' }}>\n"
                + "      <Img\n"
                + "        src={staticFile(slides[currentSlideIndex])}\n"
                + "        style={{\n"
                + "          width: '100%',\n"
                + "          height: '100%',\n"
                + "          objectFit: 'contain',\n"
                + "          opacity,\n"
                + "        }}\n"
                + "      />\n"
                + "    </AbsoluteFill>\n"
                + "  );\n"
                + "};\n"
                + "\n"
                + "export default " + compName + ";\n"
                + "export const fps = " + fps + ";\n"
                + "export const durationInFrames = " + totalFrames + ";\n";

        String createResult = createComposition(compName, code, fps, totalFrames);
        if (createResult.startsWith("FAILED")) {
            return createResult;
        }

        return renderVideo(compName, outputFilename);
    }

    @Tool(description = "Get the status of the Remotion setup - whether Node.js is installed, "
            + "Remotion project exists, dependencies are installed, etc.")
    public String getRemotionStatus() {
        notifier.notify("Checking Remotion status...");

        StringBuilder sb = new StringBuilder("Remotion Status:\n");

        String nodeVersion = systemControl.runCmd("node --version");
        boolean nodeOk = !nodeVersion.contains("error") && !nodeVersion.contains("not recognized");
        sb.append("  Node.js: ").append(nodeOk ? nodeVersion.trim() : "NOT INSTALLED").append("\n");

        String npmVersion = systemControl.runCmd("npm --version");
        boolean npmOk = !npmVersion.contains("error") && !npmVersion.contains("not recognized");
        sb.append("  npm: ").append(npmOk ? npmVersion.trim() : "NOT INSTALLED").append("\n");

        boolean projectExists = Files.exists(REMOTION_DIR.resolve("package.json"));
        sb.append("  Project: ").append(projectExists ? REMOTION_DIR.toString() : "NOT CREATED").append("\n");

        boolean depsInstalled = Files.isDirectory(REMOTION_DIR.resolve("node_modules"));
        sb.append("  Dependencies: ").append(depsInstalled ? "INSTALLED" : "NOT INSTALLED").append("\n");

        // Count compositions
        if (Files.isDirectory(COMPOSITIONS_DIR)) {
            try (Stream<Path> files = Files.list(COMPOSITIONS_DIR)) {
                long count = files.filter(p -> p.toString().endsWith(".tsx")).count();
                sb.append("  Compositions: ").append(count).append("\n");
            } catch (IOException e) {
                sb.append("  Compositions: error reading\n");
            }
        } else {
            sb.append("  Compositions: 0\n");
        }

        // Count rendered videos
        if (Files.isDirectory(OUTPUT_DIR)) {
            try (Stream<Path> files = Files.list(OUTPUT_DIR)) {
                long count = files.filter(p -> p.toString().endsWith(".mp4")).count();
                sb.append("  Rendered videos: ").append(count).append("\n");
            } catch (IOException e) {
                sb.append("  Rendered videos: error reading\n");
            }
        } else {
            sb.append("  Rendered videos: 0\n");
        }

        return sb.toString();
    }

    // ═══ Helpers ═══

    /**
     * Rewrite Root.tsx to register all compositions found in the compositions/ directory.
     * Each composition file must export: default component, fps, durationInFrames.
     */
    private void updateRootTsx() throws IOException {
        if (!Files.isDirectory(COMPOSITIONS_DIR)) return;

        List<String> compNames = new ArrayList<>();
        try (Stream<Path> files = Files.list(COMPOSITIONS_DIR)) {
            files.filter(p -> p.toString().endsWith(".tsx"))
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".tsx", "");
                        compNames.add(name);
                    });
        }

        writeRootTsx(compNames);
    }

    private void writeRootTsx(List<String> compositionNames) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("import React from 'react';\n");
        sb.append("import { Composition } from 'remotion';\n");

        for (String name : compositionNames) {
            sb.append("import ").append(name).append(", { fps as fps_").append(name)
                    .append(", durationInFrames as dur_").append(name)
                    .append(" } from './compositions/").append(name).append("';\n");
        }

        sb.append("\nexport const RemotionRoot: React.FC = () => {\n");
        sb.append("  return (\n");
        sb.append("    <>\n");

        for (String name : compositionNames) {
            sb.append("      <Composition\n");
            sb.append("        id=\"").append(name).append("\"\n");
            sb.append("        component={").append(name).append("}\n");
            sb.append("        durationInFrames={dur_").append(name).append("}\n");
            sb.append("        fps={fps_").append(name).append("}\n");
            sb.append("        width={1920}\n");
            sb.append("        height={1080}\n");
            sb.append("      />\n");
        }

        sb.append("    </>\n");
        sb.append("  );\n");
        sb.append("};\n");

        Files.writeString(REMOTION_DIR.resolve("src").resolve("Root.tsx"), sb.toString());
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "png";
    }

    // ═══ Template constants ═══

    private static final String PACKAGE_JSON = """
            {
              "name": "minsbot-remotion",
              "version": "1.0.0",
              "private": true,
              "dependencies": {
                "@remotion/cli": "4.0.242",
                "@remotion/renderer": "4.0.242",
                "react": "^18.3.1",
                "react-dom": "^18.3.1",
                "remotion": "4.0.242",
                "typescript": "^5.5.0"
              },
              "scripts": {
                "build": "remotion render src/index.ts"
              }
            }
            """;

    private static final String TSCONFIG_JSON = """
            {
              "compilerOptions": {
                "target": "ES2018",
                "module": "commonjs",
                "jsx": "react-jsx",
                "strict": true,
                "esModuleInterop": true,
                "skipLibCheck": true,
                "forceConsistentCasingInFileNames": true,
                "outDir": "./dist"
              },
              "include": ["src/**/*"]
            }
            """;

    private static final String REMOTION_CONFIG = """
            import { Config } from "@remotion/cli/config";
            Config.setVideoImageFormat("jpeg");
            Config.setOverwriteOutput(true);
            """;

    private static final String INDEX_TS = """
            import { registerRoot } from "remotion";
            import { RemotionRoot } from "./Root";
            registerRoot(RemotionRoot);
            """;
}
