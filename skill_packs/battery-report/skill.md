---
name: battery-report
description: Generate a Windows battery health report with design capacity vs current capacity, cycle count, and recent usage. Trigger on "battery health", "how's my battery", "battery report", "laptop battery check", "is my battery dying".
metadata:
  minsbot:
    emoji: "🔋"
    os: ["windows"]
---

# Battery Report

Uses Windows' built-in `powercfg /batteryreport` which produces an HTML report with months of telemetry.

## Steps

1. Run `powercfg /batteryreport /output "$env:TEMP\battery-report.html"`.
2. Parse the HTML for:
   - Design capacity
   - Full-charge capacity
   - Cycle count (if available)
   - Last 3 days of charge cycles
3. Compute **health %** = full-charge / design × 100.
4. Produce a verdict:
   - ≥ 90% → "Excellent — battery is near factory spec."
   - 70–90% → "Normal wear — typical after 1–2 years."
   - 50–70% → "Noticeable degradation — consider replacement if runtime bothers you."
   - < 50% → "Significantly degraded — recommend replacement."
5. Reply with: health %, design capacity (mWh), current capacity (mWh), cycles, verdict. Attach a link to open the full HTML report.

## Guardrails

- Only runs on Windows with a battery. If run on desktop / VM without one, say "no battery detected" and stop.
- Admin isn't required — `powercfg /batteryreport` works as the current user.
- Don't leave the HTML file in `%TEMP%` forever — offer to move it to Documents if the user wants to keep it.
