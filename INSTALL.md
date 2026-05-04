# Mins Bot — Install & Distribute

## For end users (downloading a built release)

| OS | File | How to install |
|---|---|---|
| **Windows** | `MinsBot-1.0.0.exe` | Double-click → Next → Next → Finish. Adds Start Menu group + optional desktop shortcut. Uninstaller in Add/Remove Programs. |
| **Windows (portable)** | `MinsBot-1.0.0-windows.zip` | Unzip → run `MinsBot/MinsBot.exe`. No install, no Java required. |
| **macOS** | `MinsBot-1.0.0.dmg` | Open → drag MinsBot to Applications. First launch may need right-click → Open if not notarized. |
| **Linux (Debian/Ubuntu)** | `minsbot_1.0.0_amd64.deb` | `sudo dpkg -i minsbot_1.0.0_amd64.deb` |
| **Linux (RHEL/Fedora)** | `minsbot-1.0.0.rpm` | `sudo rpm -i minsbot-1.0.0.rpm` |

## For developers (building installers)

### Prerequisites
- **JDK 17+** with `jpackage` on PATH (`java -version` should show 17+; `jpackage --version` should work)
- **Maven 3.6+**
- **Platform-specific:**
  - Windows: [WiX Toolset 3.x](https://github.com/wixtoolset/wix3/releases) for the `.exe` wizard installer (script falls back to portable folder + zip if missing)
  - macOS: Xcode Command Line Tools
  - Linux: `dpkg-deb` (for `.deb`) or `rpmbuild` (for `.rpm`)

### Build

```bash
# Windows
build-installer.bat

# macOS
chmod +x build-installer-mac.sh
./build-installer-mac.sh

# Linux
chmod +x build-installer-linux.sh
./build-installer-linux.sh
```

Output lands in `target/dist/`.

### What gets bundled
- **Fat JAR** (`mins-bot-1.0.0-SNAPSHOT.jar`) with all Spring Boot deps
- **JRE** (so end users don't need Java installed)
- **Piper TTS binary + your selected voice** if found at `~/mins_bot_data/piper/` — eliminates the first-run download
- **Icon** from `installer-assets/MinsBot.ico` (Windows). Add `MinsBot.icns` for macOS, `MinsBot.png` for Linux.

### Code signing (optional but recommended)

**Why:** Without code signing, Windows SmartScreen flags new installs as "unrecognized publisher" — scary for end users. macOS Gatekeeper will refuse to launch unsigned/unnotarized apps without a right-click bypass.

**Windows** — set environment variables before running `build-installer.bat`:
```cmd
set CODE_SIGN_CERT=C:\path\to\your-cert.pfx
set CODE_SIGN_PASSWORD=your-pfx-password
build-installer.bat
```
The script will sign the produced `.exe` with `signtool` after `jpackage` runs. Get a code-signing cert from DigiCert, Sectigo, GoDaddy, etc. (~$200-400/year) — or use [Azure Trusted Signing](https://learn.microsoft.com/en-us/azure/trusted-signing/) (~$10/month).

**macOS** — set Apple Developer credentials:
```bash
export APPLE_DEV_ID="Developer ID Application: Your Name (TEAMID)"
export APPLE_TEAM_ID="TEAMID"
export APPLE_ID="you@example.com"
export APPLE_APP_PASSWORD="app-specific-password"
./build-installer-mac.sh
```
Requires an [Apple Developer Program](https://developer.apple.com/programs/) membership ($99/year). The script auto-notarizes and staples the DMG so it opens with no Gatekeeper warning.

**Linux** — `.deb` and `.rpm` use repository signing keys (different mechanism). For per-package signing, use `dpkg-sig` or `rpm --addsign` after the build.

## Releasing a new version

```cmd
release.bat 1.0.1
```
Builds the Windows installer, tags `v1.0.1`, pushes the tag, creates a draft GitHub Release with the installer attached. Requires `gh` CLI authenticated.

## File layout after install (Windows)

```
%LOCALAPPDATA%\MinsBot\          — install root (per-user install)
├── MinsBot.exe                  — launcher
├── app/                         — fat JAR + jpackage descriptor
└── runtime/                     — bundled JRE (~50 MB)

%USERPROFILE%\mins_bot_data\     — user data (logs, voices, transcripts, memory)
├── mins-bot.log
├── piper/voices/*.onnx
├── piper/.selected-voice
├── piper/.length-scale
└── ...

%CWD%\application-secrets.properties  — API keys (created by Quick Setup modal)
%CWD%\memory\                         — bot memory + per-skill state
```

## Uninstall

| Mechanism | Cleans |
|---|---|
| Windows Add/Remove Programs | Install root + Start Menu shortcut. Leaves `%USERPROFILE%\mins_bot_data\` and `application-secrets.properties` |
| macOS — drag to Trash | App bundle. Same data persistence as above |
| `sudo apt remove minsbot` | Package files. Same data persistence |

User data is preserved on uninstall by design — reinstalling restores the user's voice picks, memory, and transcripts. To wipe everything:
```bash
# Windows
rmdir /s "%USERPROFILE%\mins_bot_data"

# macOS / Linux
rm -rf ~/mins_bot_data
```

## Auto-start on login

The first-run wizard offers a "Launch MinsBot when I log in" checkbox. If skipped during install, the user can enable it later by saying **"auto-start yourself"** in chat — handled by the `autostartmanager` skill.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `jpackage not found` | Install JDK 17+, ensure `jpackage` is on PATH |
| `WiX Toolset not detected` warning | Install [WiX 3.x](https://github.com/wixtoolset/wix3/releases) — installer falls back to portable folder + zip without it |
| Windows SmartScreen warns "unrecognized publisher" | Code-sign the `.exe` (see Code signing section) |
| macOS "MinsBot is damaged and can't be opened" | App is unsigned — right-click → Open the first time, OR notarize via Apple Developer credentials |
| Linux `.deb` fails: missing dependencies | `sudo apt-get install -f` after the failed `dpkg -i` |
| First TTS use silent | Verify `~/mins_bot_data/piper/voices/` has at least one `.onnx` voice. Run `Models` tab → install Piper voice |

## What's NOT in the installer yet

- **Auto-update** — users have to manually download new versions. Future: integrate [install4j](https://www.ej-technologies.com/install4j/) update mechanism or a `/api/version/check` endpoint that pings GitHub Releases.
- **Multi-user system install on Windows** — current build uses `--win-per-user-install`. To install for all users, drop that flag and run installer as Administrator.
- **MSI for Active Directory deployment** — change `--type exe` to `--type msi` in `build-installer.bat`.
