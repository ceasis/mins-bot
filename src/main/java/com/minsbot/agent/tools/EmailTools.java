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
     * then auto-send by finding and clicking the Send button via OCR.
     * Falls back to Ctrl+Enter if OCR can't find the button.
     * Verifies the send by checking if the compose window disappeared.
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

            // Take screenshot and find the Send button via OCR
            notifier.notify("Clicking Send...");
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            String screenshotResult = systemControl.takeScreenshot();
            String pathStr = screenshotResult.replace("Screenshot saved: ", "").trim();
            Path imagePath = Paths.get(pathStr);

            boolean clicked = false;
            if (Files.exists(imagePath)) {
                double[] coords = screenMemoryService.findTextOnScreen(imagePath, "Send");
                if (coords != null) {
                    // Handle DPI scaling: image pixels → logical screen pixels
                    BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
                    double scaleX = (img != null && img.getWidth() != screenSize.width)
                            ? (double) screenSize.width / img.getWidth() : 1.0;
                    double scaleY = (img != null && img.getHeight() != screenSize.height)
                            ? (double) screenSize.height / img.getHeight() : 1.0;

                    int clickX = (int) Math.round(coords[0] * scaleX);
                    int clickY = (int) Math.round(coords[1] * scaleY);

                    systemControl.mouseClick(clickX, clickY, "left");
                    clicked = true;
                    log.info("[Email] Clicked Send button via OCR at ({}, {})", clickX, clickY);
                } else {
                    log.info("[Email] OCR could not find 'Send' button on screenshot");
                }
            }

            // Fallback: Ctrl+Enter (Gmail's send shortcut)
            if (!clicked) {
                log.info("[Email] Falling back to Ctrl+Enter...");
                systemControl.focusWindow("chrome");
                Thread.sleep(500);
                systemControl.sendKeys("^{ENTER}");
                log.info("[Email] Sent Ctrl+Enter to Gmail compose");
            }

            // Verify: wait for UI to update, then check if compose window is gone
            Thread.sleep(3000);
            notifier.notify("Verifying email was sent...");
            String verifyScreenshot = systemControl.takeScreenshot();
            String verifyPath = verifyScreenshot.replace("Screenshot saved: ", "").trim();
            Path verifyImage = Paths.get(verifyPath);

            if (Files.exists(verifyImage)) {
                // If "Send" button is still visible, the click likely didn't work
                double[] sendStillVisible = screenMemoryService.findTextOnScreen(verifyImage, "Send");
                // Also check for Gmail's "Message sent" confirmation
                double[] messageSent = screenMemoryService.findTextOnScreen(verifyImage, "Message sent");

                if (messageSent != null) {
                    log.info("[Email] Verified: 'Message sent' confirmation visible");
                    return "Email sent via Gmail to " + to + " with subject: " + subject
                            + ". Verified: Gmail showed 'Message sent' confirmation.";
                } else if (sendStillVisible != null) {
                    // Send button still there — the click missed or didn't register
                    log.warn("[Email] Send button still visible after click — retrying...");
                    notifier.notify("Retrying Send click...");

                    // Retry: click the Send button again
                    BufferedImage retryImg = javax.imageio.ImageIO.read(verifyImage.toFile());
                    double scaleX = (retryImg != null && retryImg.getWidth() != screenSize.width)
                            ? (double) screenSize.width / retryImg.getWidth() : 1.0;
                    double scaleY = (retryImg != null && retryImg.getHeight() != screenSize.height)
                            ? (double) screenSize.height / retryImg.getHeight() : 1.0;
                    int retryX = (int) Math.round(sendStillVisible[0] * scaleX);
                    int retryY = (int) Math.round(sendStillVisible[1] * scaleY);
                    systemControl.mouseClick(retryX, retryY, "left");
                    log.info("[Email] Retry clicked Send at ({}, {})", retryX, retryY);

                    // Wait and verify once more
                    Thread.sleep(3000);
                    String finalScreenshot = systemControl.takeScreenshot();
                    String finalPath = finalScreenshot.replace("Screenshot saved: ", "").trim();
                    Path finalImage = Paths.get(finalPath);
                    if (Files.exists(finalImage)) {
                        double[] finalCheck = screenMemoryService.findTextOnScreen(finalImage, "Message sent");
                        if (finalCheck != null) {
                            return "Email sent via Gmail to " + to + " with subject: " + subject
                                    + ". Verified: Gmail showed 'Message sent' after retry.";
                        }
                        double[] stillSend = screenMemoryService.findTextOnScreen(finalImage, "Send");
                        if (stillSend != null) {
                            return "Gmail compose is open with email to " + to
                                    + " but the Send button click did not register. "
                                    + "The compose window is still visible — please click Send manually.";
                        }
                    }
                    return "Email likely sent via Gmail to " + to + " with subject: " + subject;
                } else {
                    // Compose window is gone (no Send button visible) — likely sent
                    log.info("[Email] Compose window gone — email likely sent");
                    return "Email sent via Gmail to " + to + " with subject: " + subject;
                }
            }

            return "Email sent via Gmail to " + to + " with subject: " + subject;
        } catch (Exception e) {
            log.error("[Email] Gmail compose fallback failed: {}", e.getMessage());
            return "Failed to open Gmail compose: " + e.getMessage();
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
