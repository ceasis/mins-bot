@echo off
title MinsBot
set JAVA_OPTS=-Xmx512m --add-modules javafx.controls,javafx.web,javafx.fxml --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED --add-opens javafx.web/com.sun.webkit=ALL-UNNAMED
java %JAVA_OPTS% -jar "%~dp0mins-bot.jar"
