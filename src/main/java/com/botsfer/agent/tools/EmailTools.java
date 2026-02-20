package com.botsfer.agent.tools;

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
import java.util.*;

/**
 * AI-callable email tools: send emails via SMTP (Spring Mail) and read inbox via IMAP.
 */
@Component
public class EmailTools {

    private static final Logger log = LoggerFactory.getLogger(EmailTools.class);

    private final JavaMailSender mailSender;
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

    public EmailTools(JavaMailSender mailSender, ToolExecutionNotifier notifier) {
        this.mailSender = mailSender;
        this.notifier = notifier;
    }

    @Tool(description = "Send an email to a recipient. Requires SMTP to be configured. " +
            "Use this when the user asks to send an email or message someone via email.")
    public String sendEmail(
            @ToolParam(description = "Recipient email address") String to,
            @ToolParam(description = "Email subject line") String subject,
            @ToolParam(description = "Email body text") String body) {
        notifier.notify("Sending email to: " + to);
        try {
            if (fromAddress == null || fromAddress.isBlank()) {
                return "Email not configured. Set app.email.from and spring.mail.* in application.properties.";
            }
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("[Email] Sent to {} subject='{}'", to, subject);
            return "Email sent successfully to " + to + " with subject: " + subject;
        } catch (Exception e) {
            log.error("[Email] Send failed: {}", e.getMessage());
            return "Failed to send email: " + e.getMessage();
        }
    }

    @Tool(description = "Send an HTML email to a recipient. Supports rich formatting with HTML tags.")
    public String sendHtmlEmail(
            @ToolParam(description = "Recipient email address") String to,
            @ToolParam(description = "Email subject line") String subject,
            @ToolParam(description = "HTML body content") String htmlBody) {
        notifier.notify("Sending HTML email to: " + to);
        try {
            if (fromAddress == null || fromAddress.isBlank()) {
                return "Email not configured. Set app.email.from and spring.mail.* in application.properties.";
            }
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
            return "Failed to send HTML email: " + e.getMessage();
        }
    }

    @Tool(description = "Read recent emails from the inbox via IMAP. Returns the latest messages " +
            "with sender, subject, and date. Requires IMAP to be configured.")
    public String readInbox(
            @ToolParam(description = "Maximum number of emails to read (1-50)") int maxMessages) {
        notifier.notify("Reading inbox...");
        try {
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
                // Get a preview of the body
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
