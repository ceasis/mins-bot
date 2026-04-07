@echo off
echo ========================================
echo  MinsBot Installer Builder
echo ========================================
echo.
echo Prerequisites:
echo   - JDK 17+ with jpackage on PATH
echo   - WiX Toolset 3.x on PATH (for MSI)
echo     Download: https://wixtoolset.org/
echo.
echo Building fat JAR and running jpackage...
call mvn clean package -DskipTests -Pinstaller
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo BUILD FAILED
    echo If jpackage failed, ensure WiX Toolset is installed and on PATH.
    pause
    exit /b 1
)
echo.
echo ========================================
echo  Build complete!
echo ========================================
echo.
echo Installer output:
dir target\installer\*.msi 2>nul
dir target\installer\*.exe 2>nul
echo.
pause
