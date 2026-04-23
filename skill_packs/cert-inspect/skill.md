---
name: cert-inspect
description: Fetch + parse a host's TLS certificate — issuer, expiry, chain, subject alt names. Surface expiring-soon warnings. Trigger on "check ssl cert for X", "when does cert for Y expire", "tls cert info", "ssl inspector", "is this site's cert valid".
metadata:
  minsbot:
    emoji: "🔒"
    os: ["windows", "darwin", "linux"]
---

# Cert Inspect

## Steps

1. Parse the host from the user's message. Accept bare host (`example.com`), URL (`https://example.com/foo`), host:port (`example.com:8443`). Default port: 443.
2. Connect to the host and pull the cert chain. Prefer a Java-native SSL handshake (no curl/openssl dependency).
3. For each cert in the chain, capture:
   - Subject CN + Organization
   - Issuer CN
   - SAN list
   - Not Before / Not After
   - Key algorithm + size
   - Signature algorithm
4. Check:
   - Is the leaf cert expired? (Not After < now)
   - How many days until expiry?
   - Does any cert in the chain use a deprecated sig algo (SHA-1)?
   - Does any SAN match the queried hostname?
5. Produce a tidy summary:
   ```
   example.com:443 — VALID (93 days until expiry)
   Issuer: Let's Encrypt R3
   Subject: CN=example.com
   SANs: example.com, www.example.com
   Signature: SHA-256-RSA
   Chain depth: 2
   Key: RSA 2048

   ⚠ Nothing flagged. Expires 2026-07-24.
   ```
6. If expiring in < 30 days, flag prominently at the top. If < 7 days, flag as "URGENT".

## Guardrails

- Use a short connect timeout (5s). Don't hang on dead hosts.
- Never cache fetched certs — always fresh.
- If the host uses SNI and no cert is returned, fall back to trying the bare hostname without SNI and surface which worked.
