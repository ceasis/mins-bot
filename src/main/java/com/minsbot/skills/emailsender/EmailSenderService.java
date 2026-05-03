package com.minsbot.skills.emailsender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.outreachtracker.OutreachTrackerConfig;
import com.minsbot.skills.outreachtracker.OutreachTrackerService;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends transactional / outreach emails via Resend (preferred — simple HTTP)
 * or SMTP fallback. Auto-logs to outreachtracker when available.
 *
 * Daily send cap protects against runaway loops. Cold-email at scale needs
 * a warmed-up domain + reverse DNS + SPF/DKIM/DMARC — bot won't fix that.
 */
@Service
public class EmailSenderService {

    private final EmailSenderConfig.EmailSenderProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    private LocalDate counterDay = LocalDate.now();
    private final AtomicInteger sentToday = new AtomicInteger(0);

    @Autowired(required = false) private OutreachTrackerService outreach;
    @Autowired(required = false) private OutreachTrackerConfig.OutreachTrackerProperties outreachProps;

    public EmailSenderService(EmailSenderConfig.EmailSenderProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public synchronized Map<String, Object> send(String to, String subject, String body,
                                                 String html, String campaign) throws Exception {
        if (to == null || to.isBlank() || !to.contains("@"))
            throw new IllegalArgumentException("'to' must be a valid email");
        if (subject == null) subject = "(no subject)";
        if (body == null && html == null) throw new IllegalArgumentException("body or html required");

        rotateCounterIfNeeded();
        if (sentToday.get() >= props.getDailyMaxSends())
            throw new RuntimeException("Daily send cap reached (" + props.getDailyMaxSends() + ")");

        Map<String, Object> result;
        if (!props.getResendApiKey().isBlank()) {
            result = sendViaResend(to, subject, body, html);
        } else if (!props.getSmtpHost().isBlank()) {
            result = sendViaSmtp(to, subject, body, html);
        } else {
            throw new RuntimeException("No email provider configured (set resendApiKey or smtpHost)");
        }

        sentToday.incrementAndGet();
        result.put("sentNumberToday", sentToday.get());
        result.put("dailyCap", props.getDailyMaxSends());

        if (outreach != null && outreachProps != null && outreachProps.isEnabled()) {
            try {
                outreach.log(to, "email", subject, body == null ? "(html)" : truncate(body, 200), campaign);
                result.put("loggedToOutreachTracker", true);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private synchronized void rotateCounterIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(counterDay)) {
            counterDay = today;
            sentToday.set(0);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendViaResend(String to, String subject, String body, String html) throws Exception {
        String fromAddr = props.getFromAddress();
        if (fromAddr == null || fromAddr.isBlank())
            throw new RuntimeException("fromAddress required for Resend");
        String from = props.getFromName().isBlank() ? fromAddr
                : props.getFromName() + " <" + fromAddr + ">";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", List.of(to));
        payload.put("subject", subject);
        if (html != null && !html.isBlank()) payload.put("html", html);
        if (body != null && !body.isBlank()) payload.put("text", body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.resend.com/emails"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getResendApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new RuntimeException("Resend HTTP " + r.statusCode() + ": " + truncate(r.body(), 300));
        Map<String, Object> resp = mapper.readValue(r.body(), Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("provider", "resend");
        out.put("id", resp.getOrDefault("id", ""));
        out.put("to", to);
        return out;
    }

    private Map<String, Object> sendViaSmtp(String to, String subject, String body, String html) throws Exception {
        Properties p = new Properties();
        p.put("mail.smtp.host", props.getSmtpHost());
        p.put("mail.smtp.port", String.valueOf(props.getSmtpPort()));
        p.put("mail.smtp.auth", "true");
        if (props.isSmtpStartTls()) p.put("mail.smtp.starttls.enable", "true");
        Session session = Session.getInstance(p, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(props.getSmtpUser(), props.getSmtpPassword());
            }
        });

        MimeMessage msg = new MimeMessage(session);
        String fromAddr = props.getFromAddress().isBlank() ? props.getSmtpUser() : props.getFromAddress();
        msg.setFrom(props.getFromName().isBlank() ? new InternetAddress(fromAddr)
                : new InternetAddress(fromAddr, props.getFromName()));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        msg.setSubject(subject);
        if (html != null && !html.isBlank()) msg.setContent(html, "text/html; charset=UTF-8");
        else msg.setText(body, "UTF-8");
        Transport.send(msg);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("provider", "smtp");
        out.put("to", to);
        return out;
    }

    public Map<String, Object> stats() {
        rotateCounterIfNeeded();
        return Map.of("sentToday", sentToday.get(), "dailyCap", props.getDailyMaxSends(), "day", counterDay.toString());
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
