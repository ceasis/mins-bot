package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

@Component
public class ClipboardTools {

    private final ToolExecutionNotifier notifier;

    public ClipboardTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Get the current text content of the system clipboard. Use when the user asks what is on the clipboard or to paste something.")
    public String getClipboardText() {
        notifier.notify("Reading clipboard...");
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                String text = (String) clipboard.getData(DataFlavor.stringFlavor);
                return text != null && !text.isBlank() ? text : "(clipboard is empty or contains no text)";
            }
            return "(clipboard does not contain text)";
        } catch (UnsupportedFlavorException | IOException e) {
            return "Could not read clipboard: " + e.getMessage();
        } catch (IllegalStateException e) {
            return "Clipboard unavailable: " + e.getMessage();
        }
    }

    @Tool(description = "Copy the given text to the system clipboard so the user can paste it elsewhere.")
    public String setClipboardText(
            @ToolParam(description = "The text to copy to the clipboard") String text) {
        notifier.notify("Copying to clipboard...");
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text != null ? text : ""), null);
            return "Copied to clipboard (" + (text != null ? text.length() : 0) + " characters).";
        } catch (IllegalStateException e) {
            return "Clipboard unavailable: " + e.getMessage();
        }
    }
}
