@echo off
echo Starting MinsBot...
java --add-modules javafx.controls,javafx.web,javafx.fxml ^
     --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED ^
     --add-opens javafx.web/com.sun.webkit=ALL-UNNAMED ^
     -Djava.library.path=target\javafx-natives ^
     -jar target\mins-bot-1.0.0-SNAPSHOT.jar
