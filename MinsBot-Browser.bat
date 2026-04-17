@echo off
setlocal enabledelayedexpansion

:: MinsBot-Browser — opens MinsBot in a chromeless browser window (no URL bar, no tabs).
:: Falls back through Chrome -> Edge -> Brave -> Vivaldi, then the default browser.

set "PORT=%MINS_BOT_PORT%"
if "%PORT%"=="" set "PORT=8765"
set "URL=http://localhost:%PORT%/"

:: Use a dedicated user-data-dir so the app window is isolated from your regular browsing.
set "DATA_DIR=%LOCALAPPDATA%\MinsBotBrowser"
set "APP_FLAGS=--app=%URL% --user-data-dir=""%DATA_DIR%"" --no-first-run --no-default-browser-check"

:: Chrome
for %%P in (
    "%ProgramFiles%\Google\Chrome\Application\chrome.exe"
    "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
    "%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"
) do (
    if exist %%P (
        start "" %%P %APP_FLAGS%
        exit /b 0
    )
)

:: Edge
for %%P in (
    "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
    "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
) do (
    if exist %%P (
        start "" %%P %APP_FLAGS%
        exit /b 0
    )
)

:: Brave
for %%P in (
    "%ProgramFiles%\BraveSoftware\Brave-Browser\Application\brave.exe"
    "%ProgramFiles(x86)%\BraveSoftware\Brave-Browser\Application\brave.exe"
    "%LOCALAPPDATA%\BraveSoftware\Brave-Browser\Application\brave.exe"
) do (
    if exist %%P (
        start "" %%P %APP_FLAGS%
        exit /b 0
    )
)

:: Vivaldi
for %%P in (
    "%LOCALAPPDATA%\Vivaldi\Application\vivaldi.exe"
    "%ProgramFiles%\Vivaldi\Application\vivaldi.exe"
) do (
    if exist %%P (
        start "" %%P %APP_FLAGS%
        exit /b 0
    )
)

:: Fallback: default browser (will show URL bar)
echo No Chromium browser found. Opening in default browser (URL bar will be visible)...
start "" "%URL%"
