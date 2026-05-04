#!/usr/bin/env bash
# MinsBot Linux installer builder.
# Produces .deb (Debian/Ubuntu) by default, .rpm if rpmbuild is on PATH.
#
# Requires: JDK 17+ (jpackage), and either dpkg-deb (for .deb) or rpmbuild (for .rpm).

set -euo pipefail

echo "========================================"
echo "  MinsBot Linux Installer Builder"
echo "========================================"
echo

if ! command -v jpackage >/dev/null 2>&1; then
    echo "ERROR: jpackage not found on PATH. Install JDK 17+."
    exit 1
fi

# Pick package type based on what's installed.
TYPE=""
if command -v dpkg-deb >/dev/null 2>&1; then
    TYPE="deb"
elif command -v rpmbuild >/dev/null 2>&1; then
    TYPE="rpm"
else
    echo "WARN: neither dpkg-deb nor rpmbuild found — falling back to app-image folder."
    TYPE="app-image"
fi
echo "Building: $TYPE"
echo

JAVA_HOME=${JAVA_HOME:-$(readlink -f "$(command -v javac)" | sed 's:/bin/javac::')}
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

# ── jpackage ──────────────────────────────────────────────────────────────
echo "[4/4] Packaging $TYPE..."

ICON_ARG=()
if [[ -f installer-assets/MinsBot.png ]]; then
    ICON_ARG=(--icon installer-assets/MinsBot.png)
fi

LINUX_FLAGS=()
if [[ "$TYPE" == "deb" || "$TYPE" == "rpm" ]]; then
    LINUX_FLAGS=(
        --linux-shortcut
        --linux-menu-group "AudioVideo;Office;"
        --linux-app-category utils
        --linux-package-name minsbot
    )
fi

jpackage \
    --input target/jpackage-input \
    --name MinsBot \
    --main-jar mins-bot-1.0.0-SNAPSHOT.jar \
    --main-class com.minsbot.FloatingAppLauncher \
    --type "$TYPE" \
    --runtime-image "$JAVA_HOME" \
    --dest target/dist \
    --app-version 1.0.0 \
    --vendor "Cholo Asis" \
    --description "MinsBot - Your AI Desktop Assistant" \
    --copyright "Copyright (c) 2026 Cholo Asis" \
    --java-options "-Xmx512m --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED --add-opens javafx.web/com.sun.webkit=ALL-UNNAMED" \
    "${ICON_ARG[@]}" \
    "${LINUX_FLAGS[@]}"

ARTIFACT=$(ls target/dist/minsbot* target/dist/MinsBot* 2>/dev/null | head -n1 || true)
echo "      Done: $ARTIFACT"
echo

echo "========================================"
echo "  Build complete!"
echo "========================================"
echo "Artifact: $ARTIFACT"
case "$TYPE" in
    deb) echo "Install: sudo dpkg -i $ARTIFACT" ;;
    rpm) echo "Install: sudo rpm -i $ARTIFACT" ;;
    *)   echo "Run: cd $ARTIFACT && ./bin/MinsBot" ;;
esac
echo
