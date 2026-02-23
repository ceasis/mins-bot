package com.minsbot.agent.tools;

import com.minsbot.agent.PlaylistService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * AI-callable tools for managing the auto-detected music playlist.
 * Songs are automatically detected from audio memory transcriptions and saved
 * to ~/mins_bot_data/playlist_config.txt. These tools allow querying and managing
 * the playlist.
 */
@Component
public class PlaylistTools {

    private final ToolExecutionNotifier notifier;
    private final PlaylistService playlistService;

    public PlaylistTools(ToolExecutionNotifier notifier, PlaylistService playlistService) {
        this.notifier = notifier;
        this.playlistService = playlistService;
    }

    @Tool(description = "Get the user's auto-detected music playlist. Shows songs that were "
            + "automatically identified from system audio captures. Use when the user asks "
            + "'what songs have I been listening to?', 'show my playlist', 'what music was detected?', "
            + "'show detected songs'.")
    public String getPlaylist() {
        notifier.notify("Reading playlist...");
        return playlistService.getPlaylist();
    }

    @Tool(description = "Manually add a song to the playlist. Use when the user says "
            + "'add this song to my playlist', 'remember this song', 'save this song', "
            + "or provides a specific song title and artist to add.")
    public String addToPlaylist(
            @ToolParam(description = "Song title") String title,
            @ToolParam(description = "Artist name") String artist) {
        notifier.notify("Adding to playlist...");
        return playlistService.addToPlaylist(title, artist);
    }

    @Tool(description = "Remove a song from the playlist by title (partial match, case-insensitive). "
            + "Use when the user says 'remove [song] from playlist', 'delete [song] from playlist'.")
    public String removeFromPlaylist(
            @ToolParam(description = "Song title or partial title to remove") String title) {
        notifier.notify("Removing from playlist...");
        return playlistService.removeFromPlaylist(title);
    }

    @Tool(description = "Clear the entire playlist, removing all detected songs. "
            + "Use when the user says 'clear my playlist', 'reset playlist', 'delete all songs'.")
    public String clearPlaylist() {
        notifier.notify("Clearing playlist...");
        return playlistService.clearPlaylist();
    }
}
