package com.minsbot.skills.dnslookup;

import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.*;

@Service
public class DnsLookupService {

    private static final List<String> DEFAULT_TYPES = List.of("A", "AAAA", "MX", "TXT", "NS", "CNAME", "SOA");

    private final DnsLookupConfig.DnsLookupProperties properties;

    public DnsLookupService(DnsLookupConfig.DnsLookupProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> lookup(String domain, List<String> types) throws NamingException {
        if (domain == null || domain.isBlank()) throw new IllegalArgumentException("domain required");
        List<String> recordTypes = (types == null || types.isEmpty()) ? DEFAULT_TYPES : types;

        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(properties.getTimeoutMs()));
        env.put("com.sun.jndi.dns.timeout.retries", "1");

        DirContext ctx = new InitialDirContext(env);
        Map<String, Object> records = new LinkedHashMap<>();
        try {
            Attributes attrs = ctx.getAttributes(domain, recordTypes.toArray(new String[0]));
            for (String type : recordTypes) {
                Attribute attr = attrs.get(type);
                List<String> values = new ArrayList<>();
                if (attr != null) {
                    NamingEnumeration<?> e = attr.getAll();
                    while (e.hasMore()) values.add(String.valueOf(e.next()));
                }
                records.put(type, values);
            }
        } finally {
            ctx.close();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domain", domain);
        result.put("records", records);
        return result;
    }
}
