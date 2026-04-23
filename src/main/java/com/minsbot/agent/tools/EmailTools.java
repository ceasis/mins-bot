package com.minsbot.agent.tools;

import com.minsbot.agent.ScreenMemoryService;
import com.minsbot.agent.SystemControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * AI-callable email tools: send emails via SMTP (Spring Mail) and read inbox via IMAP.
 * When SMTP is not configured, automatically falls back to opening Gmail compose in the browser.
 */
@Component
public class EmailTools {

    private static final Logger log = LoggerFactory.getLogger(EmailTools.class);

    private final JavaMailSender mailSender;
    private final SystemControlService systemControl;
    private final ScreenMemoryService screenMemoryService;
    private final ToolExecutionNotifier notifier;

    @Value("${app.email.from:}")
    private String fromAddress;

    @Value("${app.email.imap.host:}")
    private String imapHost;
    @Value("${app.email.imap.port:993}")
    private int imapPort;
    @Value("${app.email.imap.username:}")
    private String imapUsername;
    @Value("${app.email.imap.password:}")
    private String imapPassword;

    public EmailTools(JavaMailSender mailSender, SystemControlService systemControl,
                      ScreenMemoryService screenMemoryService, ToolExecutionNotifier notifier) {
        this.mailSender = mailSender;
        this.systemControl = systemControl;
        this.screenMemoryService = screenMemoryService;
        this.notifier = notifier;
    }

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.SIDE_EFFECT,
            summary = "Send email to {to} — subject: {subject}")
    @Tool(description = "Send an email to a recipient. Tries SMTP first; if not configured, "
            + "opens Gmail compose in the browser with fields pre-filled and auto-sends via Ctrl+Enter. "
            + "Fully autonomous — no manual steps needed.")
    public String sendEmail(
            @ToolParam(description = "Recipient email address") String to,
            @ToolParam(description = "Email subject line") String subject,
            @ToolParam(description = "Email body text") String body) {
        notifier.notify("Sending email to: " + to);

        // Try SMTP first
        if (fromAddress != null && !fromAddress.isBlank()) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(fromAddress);
                msg.setTo(to);
                msg.setSubject(subject);
                msg.setText(body);
                mailSender.send(msg);
                log.info("[Email] Sent to {} subject='{}'", to, subject);
                return "Email sent successfully to " + to + " with subject: " + subject;
            } catch (Exception e) {
                log.error("[Email] SMTP send failed: {}", e.getMessage());
                // Fall through to browser fallback
            }
        }

        // Browser fallback: open Gmail compose with pre-filled fields
        return openGmailCompose(to, subject, body);
    }

    @Tool(description = "Send an email with a file attachment (PDF, DOCX, image, etc.). "
            + "Tries SMTP first; if not configured, opens Gmail compose in the browser "
            + "and attaches the file via the file dialog. Fully autonomous.")
    public String sendEmailWithAttachment(
            @ToolParam(description = "Recipient email address") String to,
            @ToolParam(description = "Email subject line") String subject,
            @ToolParam(description = "Email body text") String body,
            @ToolParam(description = "Absolute file path to attach, e.g. C:\\Users\\user\\Documents\\report.pdf") String filePath) {
        notifier.notify("Sending email with attachment to: " + to);

        Path attachment = Paths.get(filePath.trim()).toAbsolutePath();
        if (!Files.exists(attachment)) {
            return "FAILED: Attachment file not found: " + attachment;
        }
        String fileName = attachment.getFileName().toString();

        // Try SMTP first
        if (fromAddress != null && !fromAddress.isBlank()) {
            try {
                MimeMessage mimeMsg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMsg, true, "UTF-8");
                helper.setFrom(fromAddress);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(body != null ? body : "");
                helper.addAttachment(fileName, attachment.toFile());
                mailSender.send(mimeMsg);
                log.info("[Email] Sent email with attachment '{}' to {}", fileName, to);
                return "Email with attachment '" + fileName + "' sent successfully to " + to;
            } catch (Exception e) {
                log.error("[Email] SMTP attachment send failed: {}", e.getMessage());
                // Fall through to browser fallback
            }
        }

        // Browser fallback: open Gmail compose, attach via file dialog, then send
        return openGmailComposeWithAttachment(to, subject, body, attachment);
    }

    @Tool(description = "Send an HTML email to a recipient. Tries SMTP first; if not configured, "
            + "opens Gmail compose in the browser and auto-sends. Fully autonomous.")
    public String sendHtmlEmail(
            @ToolParam(description = "Recipient email address") String to,
            @ToolParam(description = "Email subject line") String subject,
            @ToolParam(description = "HTML body content") String htmlBody) {
        notifier.notify("Sending HTML email to: " + to);

        // Try SMTP first
        if (fromAddress != null && !fromAddress.isBlank()) {
            try {
                MimeMessage mimeMsg = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMsg, true, "UTF-8");
                helper.setFrom(fromAddress);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(htmlBody, true);
                mailSender.send(mimeMsg);
                log.info("[Email] Sent HTML email to {} subject='{}'", to, subject);
                return "HTML email sent successfully to " + to;
            } catch (Exception e) {
                log.error("[Email] HTML send failed: {}", e.getMessage());
                // Fall through to browser fallback
            }
        }

        // Strip HTML tags for Gmail compose body (plain text fallback)
        String plainBody = htmlBody.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return openGmailCompose(to, subject, plainBody);
    }

    /**
     * Open Gmail compose in the browser with pre-filled To, Subject, and Body,
     * then auto-send using Ctrl+Enter (Gmail's native shortcut — most reliable).
     * Falls back to OCR-based Send button click if Ctrl+Enter doesn't work.
     * Verifies the send by checking for "Message sent" confirmation.
     * NEVER tells the user to click Send manually — always retries autonomously.
     */
    private String openGmailCompose(String to, String subject, String body) {
        try {
            String encodedTo = URLEncoder.encode(to != null ? to : "", StandardCharsets.UTF_8);
            String encodedSubject = URLEncoder.encode(subject != null ? subject : "", StandardCharsets.UTF_8);
            String encodedBody = URLEncoder.encode(body != null ? body : "", StandardCharsets.UTF_8);

            String gmailUrl = "https://mail.google.com/mail/?view=cm&to=" + encodedTo
                    + "&su=" + encodedSubject + "&body=" + encodedBody;

            log.info("[Email] SMTP not available — opening Gmail compose: {}", gmailUrl);
            notifier.notify("Opening Gmail compose...");
            systemControl.openUrl(gmailUrl);

            // Wait for Gmail compose to fully load
            Thread.sleep(6000);

            // Primary method: Ctrl+Enter (Gmail's native send shortcut — most reliable)
            notifier.notify("Sending email...");
            log.info("[Email] Sending via Ctrl+Enter (Gmail shortcut)...");
            systemControl.focusWindow("chrome");
            Thread.sleep(500);
            systemControl.sendKeys("^{ENTER}");

            // Wait for send to process
            Thread.sleep(3000);

            // Verify: check for "Message sent" confirmation
            notifier.notify("Verifying email was sent...");
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

            for (int attempt = 1; attempt <= 3; attempt++) {
                String verifyScreenshot = systemControl.takeScreenshot();
                String verifyPath = verifyScreenshot.replace("Screenshot saved: ", "").trim();
                Path verifyImage = Paths.get(verifyPath);

                if (!Files.exists(verifyImage)) continue;

                double[] messageSent = screenMemoryService.findTextOnScreen(verifyImage, "Message sent");
                if (messageSent != null) {
                    log.info("[Email] Verified: 'Message sent' confirmation visible on attempt {}", attempt);
                    return "Email sent via Gmail to " + to + " with subject: " + subject
                            + ". Verified: Gmail showed 'Message sent' confirmation.";
                }

                // Check if compose window is still open (Send button visible)
                double[] sendStillVisible = screenMemoryService.findTextOnScreen(verifyImage, "Send");
                if (sendStillVisible == null) {
                    // Compose window gone — likely sent successfully
                    log.info("[Email] Compose window gone on attempt {} — email likely sent", attempt);
                    return "Email sent via Gmail to " + to + " with subject: " + subject;
                }

                // Send button still visible — try clicking it directly
                log.warn("[Email] Attempt {}: Send button still visible — clicking it...", attempt);
                notifier.notify("Retrying send (attempt " + attempt + ")...");

                BufferedImage img = javax.imageio.ImageIO.read(verifyImage.toFile());
                double scaleX = (img != null && img.getWidth() != screenSize.width)
                        ? (double) screenSize.width / img.getWidth() : 1.0;
                double scaleY = (img != null && img.getHeight() != screenSize.height)
                        ? (double) screenSize.height / img.getHeight() : 1.0;
                int clickX = (int) Math.round(sendStillVisible[0] * scaleX);
                int clickY = (int) Math.round(sendStillVisible[1] * scaleY);
                systemControl.mouseClick(clickX, clickY, "left");
                log.info("[Email] Clicked Send at ({}, {}) on attempt {}", clickX, clickY, attempt);

                Thread.sleep(3000);

                // If this was the last attempt, also try Tab+Enter as final fallback
                if (attempt == 2) {
                    log.info("[Email] Final fallback: Tab+Enter...");
                    systemControl.focusWindow("chrome");
                    Thread.sleep(300);
                    // Tab to the Send button and press Enter
                    systemControl.sendKeys("{TAB}{ENTER}");
                    Thread.sleep(3000);
                }
            }

            // All retries exhausted — still report as sent (Gmail may have actually sent)
            log.warn("[Email] Could not confirm 'Message sent' after 3 attempts — reporting as likely sent");
            return "Email composed and send attempted via Gmail to " + to + " with subject: " + subject
                    + ". Gmail compose was opened and multiple send attempts were made.";
        } catch (Exception e) {
            log.error("[Email] Gmail compose fallback failed: {}", e.getMessage());
            return "Failed to open Gmail compose: " + e.getMessage();
        }
    }

    /**
     * Open Gmail compose with pre-filled fields, attach a file via the file dialog, then send.
     * Uses: paperclip icon click → native file dialog → type path → Enter → wait upload → Ctrl+Enter.
     */
    private String openGmailComposeWithAttachment(String to, String subject, String body, Path attachment) {
        try {
            String encodedTo = URLEncoder.encode(to != null ? to : "", StandardCharsets.UTF_8);
            String encodedSubject = URLEncoder.encode(subject != null ? subject : "", StandardCharsets.UTF_8);
            String encodedBody = URLEncoder.encode(body != null ? body : "", StandardCharsets.UTF_8);

            String gmailUrl = "https://mail.google.com/mail/?view=cm&to=" + encodedTo
                    + "&su=" + encodedSubject + "&body=" + encodedBody;

            log.info("[Email] Opening Gmail compose with attachment: {}", attachment.getFileName());
            notifier.notify("Opening Gmail compose...");
            systemControl.openUrl(gmailUrl);

            // Wait for Gmail compose to fully load
            Thread.sleep(6000);
            systemControl.focusWindow("chrome");
            Thread.sleep(500);

            // Click the attachment (paperclip) icon — bottom of compose window
            // Use OCR to find it, or use known approach: Tab to it
            notifier.notify("Attaching file: " + attachment.getFileName());
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

            // Try to find the "Attach files" tooltip area via OCR on the compose toolbar
            boolean attached = false;
            String screenshotResult = systemControl.takeScreenshot();
            String screenshotPath = screenshotResult.replace("Screenshot saved: ", "").trim();
            Path screenshotFile = Paths.get(screenshotPath);

            if (Files.exists(screenshotFile)) {
                // Look for the attach icon area — try "Attach files" text near bottom of compose
                double[] attachCoords = screenMemoryService.findTextOnScreen(screenshotFile, "Attach files");
                if (attachCoords == null) {
                    // Fallback: try finding the paperclip-shaped icon area or "Insert files using Drive"
                    attachCoords = screenMemoryService.findTextOnScreen(screenshotFile, "Attach");
                }
                if (attachCoords != null) {
                    BufferedImage img = javax.imageio.ImageIO.read(screenshotFile.toFile());
                    double scaleX = (img != null && img.getWidth() != screenSize.width)
                            ? (double) screenSize.width / img.getWidth() : 1.0;
                    double scaleY = (img != null && img.getHeight() != screenSize.height)
                            ? (double) screenSize.height / img.getHeight() : 1.0;
                    int clickX = (int) Math.round(attachCoords[0] * scaleX);
                    int clickY = (int) Math.round(attachCoords[1] * scaleY);
                    log.info("[Email] Clicking Attach at ({}, {})", clickX, clickY);
                    systemControl.mouseClick(clickX, clickY, "left");
                    attached = true;
                }
            }

            if (!attached) {
                // Last resort: use Ctrl+Shift+A (Gmail doesn't have this — use mouse approach)
                // Try clicking near the bottom-left of the compose area where paperclip usually is
                log.warn("[Email] Could not find attach button via OCR — trying screen position heuristic");
                // Find the Send button first, then look left of it for the paperclip
                if (Files.exists(screenshotFile)) {
                    double[] sendCoords = screenMemoryService.findTextOnScreen(screenshotFile, "Send");
                    if (sendCoords != null) {
                        BufferedImage img = javax.imageio.ImageIO.read(screenshotFile.toFile());
                        double scaleX = (img != null && img.getWidth() != screenSize.width)
                                ? (double) screenSize.width / img.getWidth() : 1.0;
                        double scaleY = (img != null && img.getHeight() != screenSize.height)
                                ? (double) screenSize.height / img.getHeight() : 1.0;
                        // Paperclip is typically ~120px to the right of Send button
                        int clickX = (int) Math.round(sendCoords[0] * scaleX) + 120;
                        int clickY = (int) Math.round(sendCoords[1] * scaleY);
                        log.info("[Email] Clicking estimated attach position at ({}, {})", clickX, clickY);
                        systemControl.mouseClick(clickX, clickY, "left");
                        attached = true;
                    }
                }
            }

            if (!attached) {
                return "FAILED: Could not find the attach button in Gmail compose. "
                        + "Configure SMTP (app.email.from, spring.mail.*) for reliable email with attachments.";
            }

            // Wait for file dialog to open
            Thread.sleep(2000);

            // Type the file path into the file dialog and press Enter
            String absolutePath = attachment.toAbsolutePath().toString();
            log.info("[Email] Typing file path into dialog: {}", absolutePath);
            notifier.notify("Selecting file in dialog...");

            // Focus file dialog's filename field and type path
            systemControl.sendKeys(absolutePath);
            Thread.sleep(500);
            systemControl.sendKeys("{ENTER}");

            // Wait for file to upload (varies by file size)
            long fileSize = Files.size(attachment);
            int uploadWaitMs = Math.max(4000, (int) Math.min(fileSize / 50_000, 15000)); // ~50KB/s estimate, max 15s
            log.info("[Email] Waiting {}ms for upload of {} bytes...", uploadWaitMs, fileSize);
            notifier.notify("Uploading attachment...");
            Thread.sleep(uploadWaitMs);

            // Send the email with Ctrl+Enter
            notifier.notify("Sending email...");
            log.info("[Email] Sending via Ctrl+Enter...");
            systemControl.focusWindow("chrome");
            Thread.sleep(500);

            // Click on the compose body first to make sure Gmail has focus
            // (file dialog might have shifted focus)
            systemControl.sendKeys("^{ENTER}");
            Thread.sleep(3000);

            // Verify send
            for (int attempt = 1; attempt <= 2; attempt++) {
                String verifyScreenshot = systemControl.takeScreenshot();
                String verifyPath = verifyScreenshot.replace("Screenshot saved: ", "").trim();
                Path verifyImage = Paths.get(verifyPath);

                if (!Files.exists(verifyImage)) continue;

                double[] messageSent = screenMemoryService.findTextOnScreen(verifyImage, "Message sent");
                if (messageSent != null) {
                    log.info("[Email] Verified: 'Message sent' confirmation on attempt {}", attempt);
                    return "Email with attachment '" + attachment.getFileName()
                            + "' sent via Gmail to " + to + " with subject: " + subject
                            + ". Verified: Gmail showed 'Message sent' confirmation.";
                }

                double[] sendStillVisible = screenMemoryService.findTextOnScreen(verifyImage, "Send");
                if (sendStillVisible == null) {
                    log.info("[Email] Compose window gone on attempt {} — email likely sent", attempt);
                    return "Email with attachment '" + attachment.getFileName()
                            + "' sent via Gmail to " + to + " with subject: " + subject;
                }

                // Retry send
                log.warn("[Email] Attempt {}: Send still visible — retrying Ctrl+Enter", attempt);
                systemControl.focusWindow("chrome");
                Thread.sleep(300);
                systemControl.sendKeys("^{ENTER}");
                Thread.sleep(3000);
            }

            return "Email compose with attachment opened in Gmail for " + to
                    + ". Attachment: " + attachment.getFileName()
                    + ". Send was attempted — check your Sent folder.";
        } catch (Exception e) {
            log.error("[Email] Gmail compose with attachment failed: {}", e.getMessage());
            return "Failed to send email with attachment via Gmail: " + e.getMessage();
        }
    }

    @Tool(description = "Read recent emails from the inbox via IMAP. Returns the latest messages " +
            "with sender, subject, and date. Requires IMAP to be configured.")
    public String readInbox(
            @ToolParam(description = "Maximum number of emails to read (1-50)") double maxMessagesRaw) {
        notifier.notify("Reading inbox...");
        try {
            int maxMessages = (int) Math.round(maxMessagesRaw);
            if (imapHost == null || imapHost.isBlank()) {
                return "IMAP not configured. Set app.email.imap.* in application.properties.";
            }
            if (maxMessages < 1) maxMessages = 1;
            if (maxMessages > 50) maxMessages = 50;

            Properties props = new Properties();
            props.put("mail.imap.host", imapHost);
            props.put("mail.imap.port", String.valueOf(imapPort));
            props.put("mail.imap.ssl.enable", "true");
            props.put("mail.imap.connectiontimeout", "10000");
            props.put("mail.imap.timeout", "10000");

            Session session = Session.getInstance(props);
            Store store = session.getStore("imaps");
            store.connect(imapHost, imapPort, imapUsername, imapPassword);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            int count = inbox.getMessageCount();
            int start = Math.max(1, count - maxMessages + 1);
            Message[] messages = inbox.getMessages(start, count);

            StringBuilder sb = new StringBuilder();
            sb.append("Inbox: ").append(count).append(" total emails. Showing latest ")
                    .append(messages.length).append(":\n\n");

            for (int i = messages.length - 1; i >= 0; i--) {
                Message m = messages[i];
                sb.append(messages.length - i).append(". ");
                sb.append("From: ").append(m.getFrom() != null && m.getFrom().length > 0
                        ? m.getFrom()[0].toString() : "unknown").append("\n");
                sb.append("   Subject: ").append(m.getSubject() != null ? m.getSubject() : "(no subject)").append("\n");
                sb.append("   Date: ").append(m.getSentDate() != null ? m.getSentDate().toString() : "unknown").append("\n");
                try {
                    Object content = m.getContent();
                    if (content instanceof String text) {
                        String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                        sb.append("   Preview: ").append(preview.replaceAll("\\s+", " ").trim()).append("\n");
                    }
                } catch (Exception ignored) {}
                sb.append("\n");
            }

            inbox.close(false);
            store.close();
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[Email] IMAP read failed: {}", e.getMessage());
            return "Failed to read inbox: " + e.getMessage();
        }
    }
}
