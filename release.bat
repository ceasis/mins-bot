@echo off
setlocal enabledelayedexpansion
:: MinsBot release script. Builds Windows installer + uploads to GitHub Releases.
:: Requires: gh CLI (github.com/cli/cli) authenticated with `gh auth login`.

echo ========================================
echo   MinsBot Release Builder
echo ========================================
echo.

set VERSION=%1
if "%VERSION%"=="" (
    echo Usage: release.bat ^<version^>
    echo Example: release.bat 1.0.0
    exit /b 1
)

where gh >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: gh CLI not on PATH. Install: https://cli.github.com/
    exit /b 1
)

:: Build Windows installer
echo [1/3] Building Windows installer...
call build-installer.bat
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: installer build failed.
    exit /b 1
)
echo.

:: Find the artifact
set INSTALLER=
for %%f in (target\dist\MinsBot-*.exe) do set INSTALLER=%%f
if "%INSTALLER%"=="" (
    for %%f in (target\MinsBot-*-windows.zip) do set INSTALLER=%%f
)
if "%INSTALLER%"=="" (
    echo ERROR: no installer artefact found in target\dist\ or target\
    exit /b 1
)
echo Artefact: %INSTALLER%
echo.

:: Tag + create release
echo [2/3] Tagging + creating GitHub release v%VERSION%...
git tag -a "v%VERSION%" -m "Release v%VERSION%" 2>nul
git push origin "v%VERSION%" 2>nul

gh release create "v%VERSION%" "%INSTALLER%" ^
    --title "MinsBot v%VERSION%" ^
    --notes "See CHANGELOG.md for details." ^
    --draft

echo.
echo [3/3] Done.
echo.
echo Release uploaded as DRAFT. Open the URL above to add release notes + publish.
echo.
pause
