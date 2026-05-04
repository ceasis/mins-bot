#!/usr/bin/env bash
# MinsBot macOS installer builder.
# Produces a .dmg drag-to-Applications disk image, optionally signed + notarized.
#
# Requires: JDK 17+ (jpackage), macOS (Apple Silicon or Intel).
# Optional: Apple Developer ID Application cert for codesign + notarization
#           (set APPLE_DEV_ID, APPLE_TEAM_ID, APPLE_ID, APPLE_APP_PASSWORD).

set -euo pipefail

echo "========================================"
echo "  MinsBot macOS Installer Builder"
echo "========================================"
echo

if ! command -v jpackage >/dev/null 2>&1; then
    echo "ERROR: jpackage not found on PATH. Install JDK 17+ (e.g. brew install temurin)."
    exit 1
fi

JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}
echo "Using JAVA_HOME=$JAVA_HOME"
echo

# ── Clean ─────────────────────────────────────────────────────────────────
echo "[1/4] Cleaning..."
rm -rf target/dist target/jpackage-input
rm -f  target/mins-bot-*.jar
echo "      Done."
echo

# ── Build fat JAR ─────────────────────────────────────────────────────────
echo "[2/4] Building fat JAR..."
mvn package -DskipTests -q
echo "      Done."
echo

# ── Stage + bundle Piper ──────────────────────────────────────────────────
echo "[3/4] Staging input + bundling local Piper voice..."
mkdir -p target/jpackage-input
cp target/mins-bot-1.0.0-SNAPSHOT.jar target/jpackage-input/

PIPER_SRC="$HOME/mins_bot_data/piper"
if [[ -d "$PIPER_SRC/piper" ]]; then
    echo "      Bundling Piper from $PIPER_SRC"
    mkdir -p target/jpackage-input/piper-bundle
    cp -R "$PIPER_SRC" target/jpackage-input/piper-bundle/
fi
echo "      Done."
echo

# ── jpackage --type dmg ───────────────────────────────────────────────────
echo "[4/4] Packaging .dmg..."

ICON_ARG=()
if [[ -f installer-assets/MinsBot.icns ]]; then
    ICON_ARG=(--icon installer-assets/MinsBot.icns)
elif [[ -f installer-assets/MinsBot.ico ]]; then
    echo "      NOTE: only .ico icon found — macOS prefers .icns. Skipping icon flag."
fi

SIGN_ARGS=()
if [[ -n "${APPLE_DEV_ID:-}" ]]; then
    echo "      Code-signing with Developer ID: $APPLE_DEV_ID"
    SIGN_ARGS=(--mac-sign --mac-signing-key-user-name "$APPLE_DEV_ID")
    if [[ -n "${APPLE_TEAM_ID:-}" ]]; then
        SIGN_ARGS+=(--mac-app-store-entitlements installer-assets/entitlements.plist || true)
    fi
fi

jpackage \
    --input target/jpackage-input \
    --name MinsBot \
    --main-jar mins-bot-1.0.0-SNAPSHOT.jar \
    --main-class com.minsbot.FloatingAppLauncher \
    --type dmg \
    --runtime-image "$JAVA_HOME" \
    --dest target/dist \
    --app-version 1.0.0 \
    --vendor "Cholo Asis" \
    --description "MinsBot - Your AI Desktop Assistant" \
    --copyright "Copyright (c) 2026 Cholo Asis" \
    --java-options "-Xmx512m --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED --add-opens javafx.web/com.sun.webkit=ALL-UNNAMED" \
    "${ICON_ARG[@]}" \
    "${SIGN_ARGS[@]}"

DMG=$(ls target/dist/MinsBot-*.dmg | head -n1 || true)
echo "      Done: $DMG"
echo

# ── Notarization (optional) ───────────────────────────────────────────────
if [[ -n "${APPLE_ID:-}" && -n "${APPLE_APP_PASSWORD:-}" && -n "${APPLE_TEAM_ID:-}" && -n "$DMG" ]]; then
    echo "[+] Submitting for Apple notarization..."
    xcrun notarytool submit "$DMG" \
        --apple-id "$APPLE_ID" \
        --team-id "$APPLE_TEAM_ID" \
        --password "$APPLE_APP_PASSWORD" \
        --wait
    xcrun stapler staple "$DMG"
    echo "      Notarized + stapled."
else
    echo "INFO: Notarization skipped. Set APPLE_ID, APPLE_APP_PASSWORD, APPLE_TEAM_ID to enable."
fi

echo
echo "========================================"
echo "  Build complete!"
echo "========================================"
echo "DMG: $DMG"
echo "Recipient drags MinsBot.app to /Applications."
echo
