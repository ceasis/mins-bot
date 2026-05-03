package com.minsbot.skills.fileopen;

import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.*;
import java.util.*;

@Service
public class FileOpenService {
    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    public Map<String, Object> openFile(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) throw new IllegalArgumentException("not found: " + path);
        try { Desktop.getDesktop().open(f); return Map.of("ok", true, "opened", f.getAbsolutePath(), "via", "Desktop.open"); }
        catch (Exception e) {
            String[] cmd = WIN ? new String[]{"cmd", "/c", "start", "", f.getAbsolutePath()}
                    : MAC ? new String[]{"open", f.getAbsolutePath()}
                    : new String[]{"xdg-open", f.getAbsolutePath()};
            new ProcessBuilder(cmd).start();
            return Map.of("ok", true, "opened", f.getAbsolutePath(), "via", "shell");
        }
    }

    public Map<String, Object> revealInExplorer(String path) throws Exception {
        Path p = Paths.get(path);
        if (!Files.exists(p)) throw new IllegalArgumentException("not found: " + path);
        String[] cmd;
        if (WIN) cmd = new String[]{"explorer.exe", "/select,", p.toAbsolutePath().toString()};
        else if (MAC) cmd = new String[]{"open", "-R", p.toAbsolutePath().toString()};
        else {
            // Linux: open the parent in the file manager
            Path parent = p.getParent();
            cmd = new String[]{"xdg-open", parent == null ? "." : parent.toString()};
        }
        new ProcessBuilder(cmd).start();
        return Map.of("ok", true, "revealed", p.toAbsolutePath().toString());
    }
}
