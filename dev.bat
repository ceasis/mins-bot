@echo off
setlocal
echo ========================================
echo   MinsBot DEV MODE (hot reload)
echo ========================================
echo.
echo  - Server only (no JavaFX floating ball)
echo  - Access at http://localhost:8765/
echo  - Static files served from src/main/resources/static/
echo  - Edit HTML/CSS/JS   -> refresh browser (instant)
echo  - Edit .java         -> save in IDE, Spring restarts in ~3s
echo.
echo  Run in a second terminal if your IDE doesn't auto-compile:
echo      mvn compile -q
echo.

:: MinsbotApplication (pure Spring Boot, no JavaFX) + devtools enabled
call mvn spring-boot:run ^
    -Dspring-boot.run.main-class=com.minsbot.MinsbotApplication ^
    -Dspring-boot.run.fork=true ^
    -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=true -Dspring.devtools.livereload.enabled=true"
