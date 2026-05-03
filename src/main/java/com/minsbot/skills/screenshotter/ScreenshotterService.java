package com.minsbot.skills.screenshotter;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ScreenshotterService {
    private final ScreenshotterConfig.ScreenshotterProperties props;
    private Path dir;

    public ScreenshotterService(ScreenshotterConfig.ScreenshotterProperties props) { this.props = props; }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    public Map<String, Object> captureFullScreen() throws Exception {
        Rectangle bounds = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        return captureRect(bounds, "full");
    }

    public Map<String, Object> captureRect(int x, int y, int w, int h) throws Exception {
        return captureRect(new Rectangle(x, y, w, h), "rect");
    }

    public Map<String, Object> captureAllScreens() throws Exception {
        Rectangle all = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
            all = all.union(gd.getDefaultConfiguration().getBounds());
        return captureRect(all, "multimon");
    }

    private Map<String, Object> captureRect(Rectangle bounds, String label) throws Exception {
        BufferedImage img = new Robot().createScreenCapture(bounds);
        String name = label + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
        Path file = dir.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return Map.of("ok", true, "path", file.toAbsolutePath().toString(),
                "width", bounds.width, "height", bounds.height, "filename", name);
    }
}
