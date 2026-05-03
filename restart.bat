@echo off
REM Hard restart for Mins Bot:
REM   1. Kill whatever is holding port 8765 (the running bot, if any)
REM   2. mvn clean package -DskipTests  (fresh jar, fresh bytecode)
REM   3. mvn spring-boot:run           (start a brand-new JVM)
REM
REM Use this whenever a Java edit needs to take effect — class reloads
REM don't happen mid-process, so a stop+start is the only way.

echo [restart] Killing any process on port 8765...
for /f "tokens=5" %%i in ('netstat -ano ^| findstr :8765 ^| findstr LISTENING') do (
    echo [restart] Killing PID %%i
    taskkill /F /PID %%i >nul 2>&1
)

echo [restart] Building...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [restart] Build failed — not starting.
    exit /b 1
)

echo [restart] Launching fresh JVM...
call mvn spring-boot:run
