@echo off
setlocal enabledelayedexpansion
echo ========================================
echo   MinsBot Windows Installer Builder
echo ========================================
echo.

:: --- Tool checks --------------------------------------------------------
where jpackage >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: jpackage not found on PATH.
    echo Install JDK 17+ and make sure it is on PATH.
    pause
    exit /b 1
)

:: WiX Toolset is required for --type exe (real wizard installer with
:: Start Menu shortcut, uninstaller, etc.). Fall back to app-image (folder
:: zip) if WiX is missing — works the same, just no setup wizard.
set INSTALLER_TYPE=exe
where candle >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo WARN: WiX Toolset not detected — falling back to portable folder + zip.
    echo       For a real .exe setup wizard, install WiX 3.x:
    echo       https://github.com/wixtoolset/wix3/releases
    echo.
    set INSTALLER_TYPE=app-image
)

:: --- Clean ---------------------------------------------------------------
echo [1/5] Stopping any running MinsBot and cleaning target...
taskkill /F /IM MinsBot.exe >nul 2>&1
if exist target\dist rmdir /s /q target\dist
if exist target\jpackage-input rmdir /s /q target\jpackage-input
if exist target\mins-bot-1.0.0-SNAPSHOT.jar del /q target\mins-bot-1.0.0-SNAPSHOT.jar
echo       Done.
echo.

:: --- Build fat JAR -------------------------------------------------------
echo [2/5] Building fat JAR (mvn package -DskipTests)...
call mvn package -DskipTests -q
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Maven build failed.
    pause
    exit /b 1
)
echo       Done.
echo.

:: --- Stage input dir + bundle Piper if present ---------------------------
echo [3/5] Staging jpackage input + bundling local Piper voice...
mkdir target\jpackage-input
copy /y target\mins-bot-1.0.0-SNAPSHOT.jar target\jpackage-input\ >nul

:: Bundle Piper binary + the default voice so first-run TTS doesn't need a
:: HuggingFace download. We pull from the dev's own ~/mins_bot_data/piper,
:: which is the same path the runtime uses — so whatever the user has tested
:: with locally is what ships.
set PIPER_SRC=%USERPROFILE%\mins_bot_data\piper
if exist "%PIPER_SRC%\piper" (
    echo       Bundling Piper from %PIPER_SRC%
    mkdir target\jpackage-input\piper-bundle
    xcopy /e /i /q /y "%PIPER_SRC%" "target\jpackage-input\piper-bundle\piper" >nul 2>&1
) else (
    echo       NOTE: No local Piper found at %PIPER_SRC% — installer will not pre-bundle voices.
    echo             Users will download a voice on first TTS use.
)
echo       Done.
echo.

:: --- jpackage ------------------------------------------------------------
echo [4/5] Packaging with jpackage (type=%INSTALLER_TYPE%)...

set ICON_ARG=
if exist installer-assets\MinsBot.ico (
    set ICON_ARG=--icon installer-assets\MinsBot.ico
)

set TYPE_FLAGS=
if /i "%INSTALLER_TYPE%"=="exe" (
    set TYPE_FLAGS=--type exe ^
        --win-dir-chooser ^
        --win-menu ^
        --win-menu-group "MinsBot" ^
        --win-shortcut ^
        --win-shortcut-prompt ^
        --win-per-user-install ^
        --win-upgrade-uuid 6f4e1c2a-1b9e-4a3f-9e62-2a1b1c8d7e10
) else (
    set TYPE_FLAGS=--type app-image
)

jpackage ^
    --input target\jpackage-input ^
    --name MinsBot ^
    --main-jar mins-bot-1.0.0-SNAPSHOT.jar ^
    --main-class com.minsbot.FloatingAppLauncher ^
    !TYPE_FLAGS! ^
    !ICON_ARG! ^
    --runtime-image "%JAVA_HOME%" ^
    --dest target\dist ^
    --app-version 1.0.0 ^
    --vendor "Cholo Asis" ^
    --description "MinsBot - Your AI Desktop Assistant" ^
    --copyright "Copyright (c) 2026 Cholo Asis" ^
    --java-options "-Xmx512m --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED --add-opens javafx.web/com.sun.webkit=ALL-UNNAMED"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: jpackage failed. Check output above.
    echo Make sure JAVA_HOME points to a full JDK 17+.
    pause
    exit /b 1
)
echo       Done.
echo.

:: --- Code signing (optional) --------------------------------------------
:: Set CODE_SIGN_CERT and CODE_SIGN_PASSWORD env vars to your .pfx + password.
:: Without signing, Windows SmartScreen will warn end users.
if defined CODE_SIGN_CERT (
    where signtool >nul 2>&1
    if !ERRORLEVEL! EQU 0 (
        echo [4b/5] Code-signing produced installer...
        if /i "%INSTALLER_TYPE%"=="exe" (
            for %%f in (target\dist\MinsBot-*.exe) do (
                signtool sign /f "%CODE_SIGN_CERT%" /p "%CODE_SIGN_PASSWORD%" ^
                    /tr http://timestamp.digicert.com /td sha256 /fd sha256 "%%f"
            )
        ) else (
            signtool sign /f "%CODE_SIGN_CERT%" /p "%CODE_SIGN_PASSWORD%" ^
                /tr http://timestamp.digicert.com /td sha256 /fd sha256 target\dist\MinsBot\MinsBot.exe
        )
    ) else (
        echo WARN: CODE_SIGN_CERT set but signtool not on PATH — skipping signing.
    )
) else (
    echo INFO: Code signing skipped. Set CODE_SIGN_CERT + CODE_SIGN_PASSWORD env vars to sign.
)
echo.

:: --- Distribution artefact -----------------------------------------------
echo [5/5] Preparing distribution artefact...
if /i "%INSTALLER_TYPE%"=="exe" (
    for %%f in (target\dist\MinsBot-*.exe) do (
        echo       Installer: %%f
        set INSTALLER_PATH=%%f
    )
) else (
    set ZIP_NAME=MinsBot-1.0.0-windows.zip
    if exist target\!ZIP_NAME! del /q target\!ZIP_NAME!
    powershell -NoProfile -Command "Compress-Archive -Path 'target\dist\MinsBot' -DestinationPath 'target\!ZIP_NAME!' -Force"
    echo       Zip: target\!ZIP_NAME!
)
echo.

echo ========================================
echo   Build complete!
echo ========================================
echo.
if /i "%INSTALLER_TYPE%"=="exe" (
    echo Wizard installer ready. Double-click to install with Next/Next/Finish UX.
    echo Adds Start Menu group, optional desktop shortcut, and a proper uninstaller.
) else (
    echo Portable folder + zip ready. Recipient unzips and runs MinsBot.exe.
    echo Install WiX 3.x for a real wizard installer next time.
)
echo.
echo TIP: For SmartScreen-friendly distribution, code-sign with an Authenticode cert.
echo      Set env vars: CODE_SIGN_CERT=path\to\cert.pfx  CODE_SIGN_PASSWORD=...
echo.
pause
