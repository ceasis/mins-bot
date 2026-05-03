package com.minsbot.skills.clipboardctl;

import org.springframework.stereotype.Service;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.*;

@Service
public class ClipboardCtlService {

    public Map<String, Object> read() throws Exception {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            String s = (String) cb.getData(DataFlavor.stringFlavor);
            return Map.of("ok", true, "length", s.length(), "text", s);
        }
        return Map.of("ok", true, "length", 0, "text", "", "note", "no text on clipboard");
    }

    public Map<String, Object> write(String text) {
        if (text == null) text = "";
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(new StringSelection(text), null);
        return Map.of("ok", true, "wroteLength", text.length());
    }

    public Map<String, Object> clear() {
        return write("");
    }
}
