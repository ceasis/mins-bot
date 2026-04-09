package com.minsbot.agent;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Detects the current Windows cursor type using native Win32 APIs via JNA.
 * Uses GetCursorInfo to read the system cursor handle and maps it to known
 * standard cursor types (arrow, hand, text/I-beam, wait, etc.).
 *
 * <p>This enables the bot to detect hover effects: when moving over a button,
 * the cursor changes from arrow to hand; over a text field, arrow to I-beam.</p>
 */
@Component
public class CursorDetector {

    private static final Logger log = LoggerFactory.getLogger(CursorDetector.class);

    /** Cursor types that indicate interactive elements. */
    public enum CursorType {
        ARROW,      // Normal pointer — not interactive
        HAND,       // Link/button — clickable
        IBEAM,      // Text input field — typeable
        WAIT,       // Busy/loading
        CROSSHAIR,  // Precision select
        SIZEALL,    // Move
        SIZENS,     // Vertical resize
        SIZEWE,     // Horizontal resize
        SIZENWSE,   // Diagonal resize NW-SE
        SIZENESW,   // Diagonal resize NE-SW
        NO,         // Not allowed
        APPSTARTING,// Busy + arrow
        HELP,       // Help (question mark)
        UNKNOWN     // Unrecognized
    }

    // Standard system cursor handles (loaded via LoadCursor)
    private static Pointer hArrow;
    private static Pointer hHand;
    private static Pointer hIbeam;
    private static Pointer hWait;
    private static Pointer hCross;
    private static Pointer hSizeAll;
    private static Pointer hSizeNS;
    private static Pointer hSizeWE;
    private static Pointer hSizeNWSE;
    private static Pointer hSizeNESW;
    private static Pointer hNo;
    private static Pointer hAppStarting;
    private static Pointer hHelp;

    private static boolean initialized = false;
    private static boolean available = false;

    // ─── JNA interfaces ──────────────────────────────────────────────────

    public interface User32Extra extends StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean GetCursorInfo(CURSORINFO pci);
        Pointer LoadCursorW(Pointer hInstance, long lpCursorName);
    }

    @Structure.FieldOrder({"cbSize", "flags", "hCursor", "ptScreenPos"})
    public static class CURSORINFO extends Structure {
        public int cbSize;
        public int flags;
        public Pointer hCursor;
        public WinDef.POINT ptScreenPos;

        public CURSORINFO() {
            cbSize = size();
        }
    }

    // Standard cursor IDs (IDC_*)
    private static final long IDC_ARROW = 32512;
    private static final long IDC_IBEAM = 32513;
    private static final long IDC_WAIT = 32514;
    private static final long IDC_CROSS = 32515;
    private static final long IDC_UPARROW = 32516;
    private static final long IDC_SIZENWSE = 32642;
    private static final long IDC_SIZENESW = 32643;
    private static final long IDC_SIZEWE = 32644;
    private static final long IDC_SIZENS = 32645;
    private static final long IDC_SIZEALL = 32646;
    private static final long IDC_NO = 32648;
    private static final long IDC_HAND = 32649;
    private static final long IDC_APPSTARTING = 32650;
    private static final long IDC_HELP = 32651;

    private synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            User32Extra user32 = User32Extra.INSTANCE;
            hArrow = user32.LoadCursorW(null, IDC_ARROW);
            hHand = user32.LoadCursorW(null, IDC_HAND);
            hIbeam = user32.LoadCursorW(null, IDC_IBEAM);
            hWait = user32.LoadCursorW(null, IDC_WAIT);
            hCross = user32.LoadCursorW(null, IDC_CROSS);
            hSizeAll = user32.LoadCursorW(null, IDC_SIZEALL);
            hSizeNS = user32.LoadCursorW(null, IDC_SIZENS);
            hSizeWE = user32.LoadCursorW(null, IDC_SIZEWE);
            hSizeNWSE = user32.LoadCursorW(null, IDC_SIZENWSE);
            hSizeNESW = user32.LoadCursorW(null, IDC_SIZENESW);
            hNo = user32.LoadCursorW(null, IDC_NO);
            hAppStarting = user32.LoadCursorW(null, IDC_APPSTARTING);
            hHelp = user32.LoadCursorW(null, IDC_HELP);
            available = hArrow != null;
            log.info("[CursorDetector] Initialized (available={})", available);
        } catch (Exception e) {
            log.warn("[CursorDetector] Init failed (not on Windows?): {}", e.getMessage());
            available = false;
        }
    }

    /** Check if cursor detection is available on this platform. */
    public boolean isAvailable() {
        init();
        return available;
    }

    /**
     * Get the current system cursor type.
     * Returns the cursor type at the current mouse position.
     */
    public CursorType getCurrentCursor() {
        init();
        if (!available) return CursorType.UNKNOWN;

        try {
            CURSORINFO info = new CURSORINFO();
            if (!User32Extra.INSTANCE.GetCursorInfo(info)) {
                return CursorType.UNKNOWN;
            }

            Pointer h = info.hCursor;
            if (h == null) return CursorType.UNKNOWN;

            if (h.equals(hHand)) return CursorType.HAND;
            if (h.equals(hIbeam)) return CursorType.IBEAM;
            if (h.equals(hArrow)) return CursorType.ARROW;
            if (h.equals(hWait)) return CursorType.WAIT;
            if (h.equals(hCross)) return CursorType.CROSSHAIR;
            if (h.equals(hSizeAll)) return CursorType.SIZEALL;
            if (h.equals(hSizeNS)) return CursorType.SIZENS;
            if (h.equals(hSizeWE)) return CursorType.SIZEWE;
            if (h.equals(hSizeNWSE)) return CursorType.SIZENWSE;
            if (h.equals(hSizeNESW)) return CursorType.SIZENESW;
            if (h.equals(hNo)) return CursorType.NO;
            if (h.equals(hAppStarting)) return CursorType.APPSTARTING;
            if (h.equals(hHelp)) return CursorType.HELP;

            // Unknown handle — likely a custom cursor from an app
            return CursorType.UNKNOWN;
        } catch (Exception e) {
            log.debug("[CursorDetector] GetCursorInfo failed: {}", e.getMessage());
            return CursorType.UNKNOWN;
        }
    }

    /** True if the cursor indicates a clickable element (hand pointer). */
    public boolean isClickable() {
        return getCurrentCursor() == CursorType.HAND;
    }

    /** True if the cursor indicates a text input field (I-beam). */
    public boolean isTextInput() {
        return getCurrentCursor() == CursorType.IBEAM;
    }

    /** True if the cursor changed from its normal arrow state (any interactive cursor). */
    public boolean isInteractive() {
        CursorType type = getCurrentCursor();
        return type == CursorType.HAND || type == CursorType.IBEAM
                || type == CursorType.CROSSHAIR || type == CursorType.HELP;
    }
}
