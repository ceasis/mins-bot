package com.minsbot.agent.tools;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Builds .docx files via Apache POI XWPF. Replaces the previous hand-rolled
 * OOXML emitter, which couldn't embed images. This writer supports:
 *
 * <ul>
 *   <li>Centered bold title</li>
 *   <li>{@code ## H2} and {@code ### H3} headings (styled, sized)</li>
 *   <li>Bulleted lists ({@code - } / {@code * })</li>
 *   <li>Inline {@code **bold**} and {@code *italic*}</li>
 *   <li>Markdown images {@code ![alt](file:///…)} → embedded picture part
 *       (file:// URIs only; URLs are skipped silently — the deliverable
 *       formatter has already downloaded every image to the workfolder)</li>
 *   <li>Hyperlinks {@code [text](url)} rendered as blue underlined runs</li>
 *   <li>Markdown horizontal rule {@code ---} → blank paragraph spacer</li>
 * </ul>
 */
final class WordDocPoiWriter {

    private static final Logger log = LoggerFactory.getLogger(WordDocPoiWriter.class);

    /** Body image cap. Wider than this and the page layout breaks. */
    private static final int MAX_IMAGE_WIDTH_EMU = Units.toEMU(450); // 6.25"

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern LINK  = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern BOLD  = Pattern.compile("\\*\\*([^*]+?)\\*\\*");

    private WordDocPoiWriter() {}

    static String write(String filePath, String title, String content) {
        try {
            Path path = Paths.get(filePath).toAbsolutePath();
            if (!path.toString().toLowerCase().endsWith(".docx")) {
                return "FAILED: Path must end with .docx";
            }
            Files.createDirectories(path.getParent());

            try (XWPFDocument doc = new XWPFDocument();
                 FileOutputStream fos = new FileOutputStream(path.toFile())) {

                // Define a "List Bullet" numbering id once so every bullet line
                // references the same numbering instance.
                BigInteger bulletNumId = createBulletNumbering(doc);

                // Title — centered, large, bold.
                XWPFParagraph t = doc.createParagraph();
                t.setAlignment(ParagraphAlignment.CENTER);
                t.setSpacingAfter(240);
                XWPFRun tr = t.createRun();
                tr.setText(title == null ? "Document" : title);
                tr.setBold(true);
                tr.setFontSize(26);

                // Body: parse markdown line by line.
                if (content != null) {
                    for (String raw : content.split("\n", -1)) {
                        renderLine(doc, raw, bulletNumId);
                    }
                }

                doc.write(fos);
            }
            log.info("[WordDoc] Created: {} ({} bytes)", path, Files.size(path));
            return "Created Word document: " + path;
        } catch (Exception e) {
            log.error("[WordDoc] POI write failed: {}", e.getMessage(), e);
            return "Failed to create Word document: " + e.getMessage();
        }
    }

    private static void renderLine(XWPFDocument doc, String raw, BigInteger bulletNumId) {
        String line = raw == null ? "" : raw.stripTrailing();
        if (line.isEmpty()) {
            doc.createParagraph(); // blank spacer
            return;
        }
        String trimmed = line.trim();

        // Markdown image — own paragraph, embedded picture part.
        Matcher imgM = IMAGE.matcher(trimmed);
        if (imgM.matches() || (imgM.find() && imgM.start() == 0 && imgM.end() == trimmed.length())) {
            embedImage(doc, imgM.group(2), imgM.group(1));
            return;
        }

        if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
            doc.createParagraph(); // visual spacer
            return;
        }

        if (trimmed.startsWith("# ")) {
            heading(doc, trimmed.substring(2).trim(), 20, true);
            return;
        }
        if (trimmed.startsWith("## ")) {
            heading(doc, trimmed.substring(3).trim(), 16, true);
            return;
        }
        if (trimmed.startsWith("### ")) {
            heading(doc, trimmed.substring(4).trim(), 13, true);
            return;
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            XWPFParagraph p = doc.createParagraph();
            p.setIndentationLeft(360);
            attachBulletNumbering(p, bulletNumId);
            renderInline(p, trimmed.substring(2).trim());
            return;
        }
        // Default: paragraph.
        XWPFParagraph p = doc.createParagraph();
        renderInline(p, trimmed);
    }

    private static void heading(XWPFDocument doc, String text, int sizePt, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(160);
        p.setSpacingAfter(80);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(bold);
        r.setFontSize(sizePt);
        r.setColor("1E3C70");
    }

    /** Render a paragraph with inline {@code **bold**}, {@code [link](url)},
     *  and the strip of any leftover image markdown segments (already handled
     *  at line level, but defensive in case one slips through). */
    private static void renderInline(XWPFParagraph p, String text) {
        if (text == null || text.isEmpty()) return;
        // Strip any inline image markdown — they're handled at line level.
        text = IMAGE.matcher(text).replaceAll("");

        // Walk: handle [text](url) links first (they take priority over bold
        // because URL text might contain *). For each non-link span, apply bold.
        int last = 0;
        Matcher lm = LINK.matcher(text);
        while (lm.find()) {
            if (lm.start() > last) {
                appendBoldAware(p, text.substring(last, lm.start()));
            }
            String label = lm.group(1);
            String href = lm.group(2);
            XWPFHyperlinkRun hr = p.createHyperlinkRun(href);
            hr.setText(label);
            hr.setColor("2962A8");
            hr.setUnderline(UnderlinePatterns.SINGLE);
            last = lm.end();
        }
        if (last < text.length()) {
            appendBoldAware(p, text.substring(last));
        }
    }

    /** Split a span on `**bold**` markers and emit alternating normal/bold runs. */
    private static void appendBoldAware(XWPFParagraph p, String span) {
        if (span == null || span.isEmpty()) return;
        Matcher bm = BOLD.matcher(span);
        int i = 0;
        boolean any = false;
        while (bm.find()) {
            any = true;
            if (bm.start() > i) {
                XWPFRun r = p.createRun();
                r.setText(stripStrayItalics(span.substring(i, bm.start())));
            }
            XWPFRun r = p.createRun();
            r.setText(stripStrayItalics(bm.group(1)));
            r.setBold(true);
            i = bm.end();
        }
        if (i < span.length()) {
            XWPFRun r = p.createRun();
            r.setText(stripStrayItalics(span.substring(i)));
        }
        if (!any && i == 0) {
            // No bold markers found.
            XWPFRun r = p.createRun();
            r.setText(stripStrayItalics(span));
        }
    }

    /** Strip lone single-asterisk markers so the literal {@code *} doesn't
     *  show up in the rendered docx. POI doesn't emulate italics from
     *  markdown for free, and a stray {@code *} reads as garbage. */
    private static String stripStrayItalics(String s) {
        if (s == null) return "";
        return s.replaceAll("(?<!\\*)\\*(?!\\*)", "");
    }

    private static void embedImage(XWPFDocument doc, String url, String alt) {
        if (url == null || url.isBlank()) return;
        try {
            byte[] bytes;
            if (url.startsWith("file:")) {
                Path local = Paths.get(URI.create(url));
                if (!Files.isRegularFile(local)) {
                    log.warn("[WordDoc] image file missing, skipping: {}", local);
                    return;
                }
                bytes = Files.readAllBytes(local);
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                // The deliverable formatter pre-downloads every section image
                // into the workfolder and rewrites the markdown to file://
                // URIs. A remote URL reaching this writer means something
                // skipped that step — skip embed instead of blocking on HTTP.
                log.warn("[WordDoc] remote image URL encountered (formatter should have localized it): {}", url);
                return;
            } else {
                Path local = Paths.get(url);
                if (!Files.isRegularFile(local)) return;
                bytes = Files.readAllBytes(local);
            }

            int picType = guessPoiPictureType(bytes, url);
            int[] dims = readImageDims(bytes);
            int wEmu = MAX_IMAGE_WIDTH_EMU;
            int hEmu = (dims[0] > 0 && dims[1] > 0)
                    ? (int) ((long) wEmu * dims[1] / dims[0])
                    : Units.toEMU(300);

            XWPFParagraph p = doc.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);
            p.setSpacingBefore(120);
            p.setSpacingAfter(120);
            XWPFRun r = p.createRun();
            try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                r.addPicture(in, picType, alt == null ? "image" : alt, wEmu, hEmu);
            }
        } catch (Exception e) {
            log.warn("[WordDoc] embed image failed for {}: {}", url, e.getMessage());
        }
    }

    private static int guessPoiPictureType(byte[] bytes, String url) {
        if (bytes != null && bytes.length >= 4) {
            int b0 = bytes[0] & 0xff, b1 = bytes[1] & 0xff, b2 = bytes[2] & 0xff, b3 = bytes[3] & 0xff;
            if (b0 == 0xFF && b1 == 0xD8) return XWPFDocument.PICTURE_TYPE_JPEG;
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return XWPFDocument.PICTURE_TYPE_PNG;
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return XWPFDocument.PICTURE_TYPE_GIF;
        }
        String low = url == null ? "" : url.toLowerCase();
        if (low.endsWith(".png")) return XWPFDocument.PICTURE_TYPE_PNG;
        if (low.endsWith(".gif")) return XWPFDocument.PICTURE_TYPE_GIF;
        return XWPFDocument.PICTURE_TYPE_JPEG;
    }

    private static int[] readImageDims(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) return new int[]{0, 0};
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    /** Create a one-time bullet numbering definition so List Bullet paragraphs
     *  can reference it. POI doesn't ship a default; we add a minimal num/abstractNum
     *  pair via the XWPFNumbering API. */
    private static BigInteger createBulletNumbering(XWPFDocument doc) {
        try {
            XWPFNumbering numbering = doc.createNumbering();
            // POI doesn't expose a clean "addBullet()" — we build the abstract
            // numbering by hand. BigInteger.ONE is fine as the first abstract id.
            BigInteger abstractId = BigInteger.ONE;
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum cta =
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum.Factory.newInstance();
            cta.setAbstractNumId(abstractId);
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl lvl = cta.addNewLvl();
            lvl.setIlvl(BigInteger.ZERO);
            lvl.addNewNumFmt().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat.BULLET);
            lvl.addNewLvlText().setVal("•");
            org.apache.poi.xwpf.usermodel.XWPFAbstractNum abstractNum =
                    new org.apache.poi.xwpf.usermodel.XWPFAbstractNum(cta);
            BigInteger absId = numbering.addAbstractNum(abstractNum);
            return numbering.addNum(absId);
        } catch (Exception e) {
            log.warn("[WordDoc] could not create bullet numbering: {}", e.getMessage());
            return null;
        }
    }

    private static void attachBulletNumbering(XWPFParagraph p, BigInteger numId) {
        if (numId == null) return;
        try {
            CTNumPr numPr = p.getCTP().getPPr() != null && p.getCTP().getPPr().getNumPr() != null
                    ? p.getCTP().getPPr().getNumPr()
                    : (p.getCTP().getPPr() == null
                            ? p.getCTP().addNewPPr().addNewNumPr()
                            : p.getCTP().getPPr().addNewNumPr());
            CTDecimalNumber id = numPr.getNumId() == null ? numPr.addNewNumId() : numPr.getNumId();
            id.setVal(numId);
            CTDecimalNumber ilvl = numPr.getIlvl() == null ? numPr.addNewIlvl() : numPr.getIlvl();
            ilvl.setVal(BigInteger.ZERO);
        } catch (Exception ignored) {}
    }
}
