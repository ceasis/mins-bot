---
name: network-diagnose
description: Run a layered network diagnostic — check gateway, DNS, public internet, and a specific host — and surface which step failed. Trigger on "why is my internet slow", "diagnose my network", "why can't I reach X", "ping test", "internet check".
metadata:
  minsbot:
    emoji: "📡"
    os: ["windows", "darwin", "linux"]
---

# Network Diagnose

Five quick probes. Each one answers a different question. The first failure tells you where the break is.

## Steps

1. **Loopback** — `ping 127.0.0.1 -n 2` (Windows) / `-c 2` (Unix). If this fails, the TCP/IP stack is broken; stop.
2. **Gateway** — get default gateway (`route print 0.0.0.0` on Windows, `ip route | grep default` on Unix), ping it. Fail → local network issue (Wi-Fi / cable / router).
3. **Public DNS resolvable** — ping `1.1.1.1`. Fail → gateway has no WAN.
4. **DNS working** — `nslookup github.com` (Windows) or `dig github.com +short` (Unix). Fail → DNS server issue; try switching to 1.1.1.1.
5. **Target host** (if user named one) — ping + `tracert/traceroute` for 5 hops. If tracert dies after hop N, that's the faulty segment.
6. Produce a verdict table:
   ```
   loopback:    OK
   gateway:     OK (192.168.1.1, 2ms)
   wan:         OK (1.1.1.1, 18ms)
   dns:         FAIL — nslookup returned no answer
   target:      skipped (DNS unreachable)
   → Fix: change DNS to 1.1.1.1 (Wi-Fi adapter → IPv4 properties)
   ```

## Guardrails

- Cap all pings at 2–3 packets and 2-second timeout. Don't hang if something's unreachable.
- Tracert output: don't dump all hops — show first 3, "…", last 3 if > 10 hops.
- Never modify network config automatically. Tell the user what to change; don't do it.
