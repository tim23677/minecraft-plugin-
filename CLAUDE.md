# OrderHub

Kommerzielles Minecraft-Plugin für Paper und Folia. Java 21, Gradle Kotlin DSL, Paper API 1.21.11, SQLite und MariaDB/MySQL über HikariCP.

## Befehle

- `./gradlew build` kompiliert (mit -Werror), prüft Spotless, SpotBugs, führt Tests aus und baut das Shadow-Jar.
- `./gradlew spotlessApply` formatiert.
- `./gradlew test` startet die MockBukkit- und Unit-Tests.
- `./gradlew integrationTest` startet Tests mit `@Tag("integration")` gegen Testcontainers. Braucht einen Docker-Daemon, sonst werden sie übersprungen.
- `./gradlew pitest` misst die Mutationsabdeckung.
- `./gradlew dependencyCheckAnalyze` prüft Abhängigkeiten auf bekannte Schwachstellen. Braucht die Umgebungsvariable `NVD_API_KEY`.
- `ACCEPT_MINECRAFT_EULA=true tools/folia-test/run-folia-smoke.sh` startet einen echten Folia-Server. Die EULA-Zustimmung liegt beim Nutzer.

## Regeln

- Tasks nur über `dev.orderhub.scheduler.PluginScheduler` starten, nie über `Bukkit.getScheduler()`.
- Keine Datenbankzugriffe auf Server- oder Region-Threads.
- Die Hauptklasse bleibt nicht-final, MockBukkit erzeugt eine Unterklasse.
- Keine Prüfung (SpotBugs, -Werror, Dependency-Check) abschalten, um einen Build grün zu machen. Befunde einzeln und begründet unterdrücken.
- Keine Zugangsdaten ins Repository. `NVD_API_KEY` und Datenbankpasswörter nur aus der Umgebung.

Dokumentation der Werkzeuge: `tools/README.md`.

## Skills

Die Skills in `.claude/skills` sind Vorschläge. Bei Widerspruch zu dieser Datei gilt diese Datei. Die Paper-Skills (`paper-*`, `minecraft-*`) liefern Muster für Paper 1.21.11 und 26.x, vor der Übernahme gegen das OrderHub-Design prüfen.
