@echo off
setlocal enabledelayedexpansion
echo ========================================
echo   MinsBot Windows Installer Builder
echo ========================================
echo.

:: Check jpackage
where jpackage >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: jpackage not found on PATH.
    echo Install JDK 17+ and make sure it is on PATH.
    pause
    exit /b 1
)

echo [1/4] Stopping any running MinsBot and cleaning target...
taskkill /F /IM MinsBot.exe >nul 2>&1
if exist target\dist rmdir /s /q target\dist
if exist target\classes rmdir /s /q target\classes
if exist target\jpackage-input rmdir /s /q target\jpackage-input
if exist target\mins-bot-1.0.0-SNAPSHOT.jar del /q target\mins-bot-1.0.0-SNAPSHOT.jar
echo       Done.
echo.

echo [1b/4] Building fat JAR...
call mvn package -DskipTests -q
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Maven build failed.
    pause
    exit /b 1
)
echo       Done.
echo.

echo [2/4] Preparing clean input directory...
if exist target\jpackage-input rmdir /s /q target\jpackage-input
if exist target\dist rmdir /s /q target\dist
mkdir target\jpackage-input
copy /y target\mins-bot-1.0.0-SNAPSHOT.jar target\jpackage-input\ >nul
echo       Done.
echo.

echo [3/4] Packaging with jpackage (bundles JRE — no Java needed on target machine)...
jpackage ^
    --input target\jpackage-input ^
    --name MinsBot ^
    --main-jar mins-bot-1.0.0-SNAPSHOT.jar ^
    --main-class com.minsbot.FloatingAppLauncher ^
    --type app-image ^
    --runtime-image "%JAVA_HOME%" ^
    --dest target\dist ^
    --app-version 1.0.0 ^
    --vendor "Cholo Asis" ^
    --description "MinsBot - Your AI Desktop Assistant" ^
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

echo [4/4] Zipping for distribution...
set ZIP_NAME=MinsBot-1.0.0-windows.zip
if exist target\%ZIP_NAME% del /q target\%ZIP_NAME%
tar -czf target\%ZIP_NAME% -C target\dist MinsBot
if %ERRORLEVEL% NEQ 0 (
    echo WARNING: tar failed, trying PowerShell...
    powershell -NoProfile -Command "Compress-Archive -Path 'target\dist\MinsBot' -DestinationPath 'target\%ZIP_NAME%' -Force"
)
echo       Done.
echo.

echo ========================================
echo   Build complete!
echo ========================================
echo.
echo App folder:    %CD%\target\dist\MinsBot\
echo Launcher:      %CD%\target\dist\MinsBot\MinsBot.exe
if exist target\%ZIP_NAME% (
    echo Zip (share):   %CD%\target\%ZIP_NAME%
    echo.
    echo To distribute: share the zip. Recipient unzips and runs MinsBot.exe.
    echo No Java installation needed on their machine.
)
echo.
echo TIP: For a proper setup wizard installer (.exe with install/uninstall),
echo install WiX Toolset 3.x then change --type to exe in this script.
echo Download: https://github.com/wixtoolset/wix3/releases
echo.
pause
