---
name: uptime-check
description: Show PC uptime, last boot time, and an explanation of what's been running since boot. Trigger on "uptime", "when did I last restart", "how long has my pc been on", "last boot", "is my pc overdue for a restart".
metadata:
  minsbot:
    emoji: "⏱️"
    os: ["windows", "darwin", "linux"]
---

# Uptime Check

Windows devs often lose track of when they last rebooted — Windows Update postponed, session restored. This surfaces it plainly.

## Steps

1. On Windows: run `net statistics workstation` and parse the `Statistics since` line; OR `(Get-CimInstance Win32_OperatingSystem).LastBootUpTime` via PowerShell. Prefer the PowerShell path.
2. On macOS/Linux: run `uptime -s` for boot time, `uptime -p` for pretty duration.
3. Compute the delta from boot to now.
4. Reply with:
   - Boot time (local time, ISO + weekday)
   - Uptime in human form ("4 days, 7 hours")
   - Soft recommendation:
     - < 3 days: "Recent reboot, no action needed."
     - 3–14 days: "Fine — Windows/macOS handles this."
     - > 14 days: "Overdue — consider a reboot to apply pending updates + clear memory fragmentation."

## Guardrails

- Never offer to reboot automatically. This is information-only.
- If the system clock looks wrong (boot time in the future), surface that rather than reporting nonsense uptime.
