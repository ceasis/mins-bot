package com.minsbot.skills.emailvalidator;

import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class EmailValidatorService {

    // RFC 5322-lite: good enough for real-world checks
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}$"
    );

    private final EmailValidatorConfig.EmailValidatorProperties properties;

    public EmailValidatorService(EmailValidatorConfig.EmailValidatorProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> validate(String email) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", email);
        if (email == null || email.isBlank()) {
            out.put("valid", false);
            out.put("reason", "empty");
            return out;
        }
        String trimmed = email.trim();
        boolean syntaxValid = EMAIL.matcher(trimmed).matches() && trimmed.length() <= 254;
        out.put("syntaxValid", syntaxValid);
        if (!syntaxValid) {
            out.put("valid", false);
            out.put("reason", "syntax");
            return out;
        }

        int at = trimmed.lastIndexOf('@');
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at + 1).toLowerCase();
        out.put("localPart", local);
        out.put("domain", domain);
        out.put("localLengthValid", local.length() >= 1 && local.length() <= 64);

        boolean disposable = properties.getDisposableDomains().contains(domain);
        out.put("disposable", disposable);

        boolean hasMx = false;
        List<String> mxHosts = new ArrayList<>();
        if (properties.isCheckMx()) {
            try {
                Hashtable<String, String> env = new Hashtable<>();
                env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(properties.getTimeoutMs()));
                env.put("com.sun.jndi.dns.timeout.retries", "1");
                DirContext ctx = new InitialDirContext(env);
                try {
                    Attributes attrs = ctx.getAttributes(domain, new String[]{"MX", "A"});
                    Attribute mx = attrs.get("MX");
                    if (mx != null) {
                        NamingEnumeration<?> e = mx.getAll();
                        while (e.hasMore()) mxHosts.add(String.valueOf(e.next()));
                        hasMx = !mxHosts.isEmpty();
                    }
                    if (!hasMx && attrs.get("A") != null) {
                        hasMx = true; // fallback per RFC 5321 §5.1
                        mxHosts.add("(implicit via A record)");
                    }
                } finally {
                    ctx.close();
                }
            } catch (Exception e) {
                out.put("mxError", e.getMessage());
            }
            out.put("hasMx", hasMx);
            out.put("mxHosts", mxHosts);
        }

        boolean valid = syntaxValid && (!properties.isCheckMx() || hasMx);
        out.put("valid", valid);
        if (disposable) out.put("warning", "disposable email domain");
        return out;
    }
}
