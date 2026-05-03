package com.minsbot.agent.tools;

import com.minsbot.skills.musicplayer.MusicPlayerConfig;
import com.minsbot.skills.musicplayer.MusicPlayerService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lets the chat agent ACTUALLY play music — searches the configured library
 * for filename matches, opens in OS default player, falls back to YouTube
 * Music search if nothing matches locally.
 */
@Component
public class MusicPlayerTools {

    @Autowired(required = false) private MusicPlayerService svc;
    @Autowired(required = false) private MusicPlayerConfig.MusicPlayerProperties props;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Play music. Use when the user says 'play music', 'play <song name>', "
            + "'play some music', 'put on <artist>', 'play a song'. With no query → plays a random "
            + "track from the configured library. With a query → searches the library by filename "
            + "for the best fuzzy match. Falls back to opening YouTube Music search in the browser "
            + "if nothing matches locally. Library defaults to ~/Music; configure additional folders "
            + "via app.skills.musicplayer.library-paths.")
    public String playMusic(@ToolParam(description = "What to play. Empty = random pick from library.", required = false) String query) {
        if (svc == null || props == null) return "musicplayer skill not loaded.";
        if (!props.isEnabled()) return "musicplayer skill is disabled. Set app.skills.musicplayer.enabled=true.";
        if (notifier != null) notifier.notify("🎵 starting music...");
        try {
            Map<String, Object> r = (query == null || query.isBlank()) ? svc.playRandom() : svc.searchAndPlay(query);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                if (r.get("playing") != null) return "🎵 playing: " + r.get("playing");
                if (r.get("matched") != null) return "🎵 matched (score=" + r.get("matchScore") + "): " + r.get("matched");
                if (r.get("openedYoutube") != null) return "🎵 nothing matched locally — opened YouTube Music: " + r.get("openedYoutube");
            }
            return "✗ " + r.get("error");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Play a specific audio file by absolute path. Use when the user says "
            + "'play this file: <path>', 'open and play X.mp3'.")
    public String playFile(@ToolParam(description = "Absolute path to audio file") String path) {
        if (svc == null || props == null || !props.isEnabled()) return "musicplayer skill is disabled.";
        try {
            Map<String, Object> r = svc.playPath(path);
            return "🎵 playing: " + r.get("playing");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Open YouTube Music search for a query (artist/song/playlist) in the default "
            + "browser. Use when the user says 'find <song> on YouTube', 'play <X> on YT', 'youtube "
            + "<artist>'.")
    public String youtubeMusic(@ToolParam(description = "Search query") String query) {
        if (svc == null || props == null || !props.isEnabled()) return "musicplayer skill is disabled.";
        try { return "🎵 opened: " + svc.openYoutubeSearch(query).get("openedYoutube"); }
        catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Show how many tracks are in the music library and where they're located. "
            + "Use when the user says 'how big is my music library', 'how many songs do I have', "
            + "'where's my music'.")
    public String libraryInfo() {
        if (svc == null || props == null || !props.isEnabled()) return "musicplayer skill is disabled.";
        try {
            Map<String, Object> r = svc.listLibrary(5);
            return "🎵 " + r.get("totalTracks") + " tracks across " + r.get("libraryPaths") + "\nSample:\n  "
                    + String.join("\n  ", (java.util.List<String>) r.get("sample"));
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }
}
