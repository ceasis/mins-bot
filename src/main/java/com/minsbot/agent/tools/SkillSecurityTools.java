package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.certinspector.CertInspectorConfig;
import com.minsbot.skills.certinspector.CertInspectorService;
import com.minsbot.skills.cvelookup.CveLookupConfig;
import com.minsbot.skills.cvelookup.CveLookupService;
import com.minsbot.skills.dnslookup.DnsLookupConfig;
import com.minsbot.skills.dnslookup.DnsLookupService;
import com.minsbot.skills.emailvalidator.EmailValidatorConfig;
import com.minsbot.skills.emailvalidator.EmailValidatorService;
import com.minsbot.skills.headeraudit.HeaderAuditConfig;
import com.minsbot.skills.headeraudit.HeaderAuditService;
import com.minsbot.skills.hibpcheck.HibpCheckConfig;
import com.minsbot.skills.hibpcheck.HibpCheckService;
import com.minsbot.skills.jwtinspector.JwtInspectorConfig;
import com.minsbot.skills.jwtinspector.JwtInspectorService;
import com.minsbot.skills.netinfo.NetInfoConfig;
import com.minsbot.skills.netinfo.NetInfoService;
import com.minsbot.skills.passwordstrength.PasswordStrengthConfig;
import com.minsbot.skills.passwordstrength.PasswordStrengthService;
import com.minsbot.skills.secretsscan.SecretsScanConfig;
import com.minsbot.skills.secretsscan.SecretsScanService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SkillSecurityTools {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolExecutionNotifier notifier;

    private final PasswordStrengthService pwd; private final PasswordStrengthConfig.PasswordStrengthProperties pwdProps;
    private final HibpCheckService hibp; private final HibpCheckConfig.HibpCheckProperties hibpProps;
    private final JwtInspectorService jwt; private final JwtInspectorConfig.JwtInspectorProperties jwtProps;
    private final CertInspectorService cert; private final CertInspectorConfig.CertInspectorProperties certProps;
    private final HeaderAuditService headers; private final HeaderAuditConfig.HeaderAuditProperties headerProps;
    private final DnsLookupService dns; private final DnsLookupConfig.DnsLookupProperties dnsProps;
    private final CveLookupService cve; private final CveLookupConfig.CveLookupProperties cveProps;
    private final SecretsScanService secrets; private final SecretsScanConfig.SecretsScanProperties secretsProps;
    private final EmailValidatorService email; private final EmailValidatorConfig.EmailValidatorProperties emailProps;
    private final NetInfoService net; private final NetInfoConfig.NetInfoProperties netProps;

    public SkillSecurityTools(ToolExecutionNotifier notifier,
                              PasswordStrengthService pwd, PasswordStrengthConfig.PasswordStrengthProperties pwdProps,
                              HibpCheckService hibp, HibpCheckConfig.HibpCheckProperties hibpProps,
                              JwtInspectorService jwt, JwtInspectorConfig.JwtInspectorProperties jwtProps,
                              CertInspectorService cert, CertInspectorConfig.CertInspectorProperties certProps,
                              HeaderAuditService headers, HeaderAuditConfig.HeaderAuditProperties headerProps,
                              DnsLookupService dns, DnsLookupConfig.DnsLookupProperties dnsProps,
                              CveLookupService cve, CveLookupConfig.CveLookupProperties cveProps,
                              SecretsScanService secrets, SecretsScanConfig.SecretsScanProperties secretsProps,
                              EmailValidatorService email, EmailValidatorConfig.EmailValidatorProperties emailProps,
                              NetInfoService net, NetInfoConfig.NetInfoProperties netProps) {
        this.notifier = notifier;
        this.pwd = pwd; this.pwdProps = pwdProps;
        this.hibp = hibp; this.hibpProps = hibpProps;
        this.jwt = jwt; this.jwtProps = jwtProps;
        this.cert = cert; this.certProps = certProps;
        this.headers = headers; this.headerProps = headerProps;
        this.dns = dns; this.dnsProps = dnsProps;
        this.cve = cve; this.cveProps = cveProps;
        this.secrets = secrets; this.secretsProps = secretsProps;
        this.email = email; this.emailProps = emailProps;
        this.net = net; this.netProps = netProps;
    }

    @Tool(description = "Evaluate password strength: entropy, character classes, patterns, crack-time estimate, 0-100 score.")
    public String evaluatePasswordStrength(@ToolParam(description = "Password to evaluate") String password) {
        if (!pwdProps.isEnabled()) return disabled("passwordstrength");
        try { return toJson(pwd.evaluate(password)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Check if a password appears in known breach corpora via Have I Been Pwned (k-anonymity: only SHA1 prefix is sent).")
    public String checkPwnedPassword(@ToolParam(description = "Password to check") String password) {
        if (!hibpProps.isEnabled()) return disabled("hibpcheck");
        try { return toJson(hibp.check(password)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Decode a JWT token and show header + payload + expiry status. Does NOT verify the signature (use jwtVerify for that).")
    public String decodeJwt(@ToolParam(description = "JWT token (three dot-separated parts)") String token) {
        if (!jwtProps.isEnabled()) return disabled("jwtinspector");
        try { return toJson(jwt.decode(token)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Inspect an SSL/TLS certificate for host:port — subject, issuer, SAN, expiry, chain length.")
    public String inspectSslCert(
            @ToolParam(description = "Hostname") String host,
            @ToolParam(description = "Port (default 443)") double port) {
        if (!certProps.isEnabled()) return disabled("certinspector");
        try { return toJson(cert.inspect(host, (int) (port <= 0 ? 443 : port))); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Audit HTTP security headers of a URL (HSTS, CSP, X-Frame-Options, Referrer-Policy, etc.) and return a 0-100 score.")
    public String auditHttpHeaders(@ToolParam(description = "URL to audit") String url) {
        if (!headerProps.isEnabled()) return disabled("headeraudit");
        try { return toJson(headers.audit(url)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "DNS lookup: A/AAAA/MX/TXT/NS/CNAME/SOA records for a domain.")
    public String dnsLookup(
            @ToolParam(description = "Domain name") String domain,
            @ToolParam(description = "Comma-separated record types (e.g. 'A,MX,TXT') — leave empty for default set") String types) {
        if (!dnsProps.isEnabled()) return disabled("dnslookup");
        try {
            List<String> typeList = (types == null || types.isBlank()) ? null : List.of(types.split("\\s*,\\s*"));
            return toJson(dns.lookup(domain, typeList));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Look up a CVE by ID or keyword in the NVD database.")
    public String lookupCve(
            @ToolParam(description = "Either a CVE ID (e.g. 'CVE-2024-1234') or a search keyword") String cveIdOrKeyword) {
        if (!cveProps.isEnabled()) return disabled("cvelookup");
        try {
            if (cveIdOrKeyword.matches("(?i)CVE-\\d{4}-\\d{4,}")) return toJson(cve.lookup(cveIdOrKeyword));
            return toJson(cve.search(cveIdOrKeyword, 20));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Scan text for leaked secrets: AWS keys, GitHub/GitLab tokens, Stripe/OpenAI/Anthropic keys, private keys, etc. Matches are redacted by default.")
    public String scanSecrets(@ToolParam(description = "Text to scan") String text) {
        if (!secretsProps.isEnabled()) return disabled("secretsscan");
        try { return toJson(secrets.scan(text, true, secretsProps.getRedactKeepChars())); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Validate an email address: syntax, MX record, and disposable-provider check.")
    public String validateEmail(@ToolParam(description = "Email address") String emailAddress) {
        if (!emailProps.isEnabled()) return disabled("emailvalidator");
        return toJson(email.validate(emailAddress));
    }

    @Tool(description = "Get local network info: host info, interfaces, or public IP. What: 'host', 'interfaces', or 'public-ip'.")
    public String networkInfo(@ToolParam(description = "What to look up: 'host', 'interfaces', or 'public-ip'") String what) {
        if (!netProps.isEnabled()) return disabled("netinfo");
        try {
            return switch (what.toLowerCase()) {
                case "host" -> toJson(net.hostInfo());
                case "interfaces" -> toJson(net.interfaces());
                case "public-ip", "publicip" -> toJson(java.util.Map.of("ip", net.publicIp()));
                default -> "Unknown: " + what;
            };
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }
    private String toJson(Object obj) { try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); } }
}
