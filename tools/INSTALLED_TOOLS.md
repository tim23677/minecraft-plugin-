# Installierte Werkzeuge

Statusstufen: getestet (ausgeführt, Ergebnis geprüft), konfiguriert (in der Build-Datei, aber nicht ausführbar getestet), vorbereitet (Dateien liegen bereit, Ausführung braucht eine Handlung des Nutzers), nicht installiert.

| Werkzeug | Version | Status | Beleg |
| --- | --- | --- | --- |
| Gradle Wrapper | 9.9.2 | getestet | `./gradlew build` erfolgreich |
| Paper API (compileOnly) | 1.21.11-R0.1-SNAPSHOT | getestet | Kompilierung mit -Xlint:all -Werror |
| JUnit Jupiter | 6.0.3 | getestet | 10 Tests, 9 davon mit MockBukkit |
| MockBukkit | 4.116.3 (mockbukkit-v1.21) | getestet | Plugin laden, Command, Menü, Klick, Scheduler |
| AssertJ | 3.27.7 | getestet | in allen Tests |
| Spotless (google-java-format, ktlint) | 8.12.0 | getestet | `spotlessCheck` erfolgreich |
| SpotBugs | Plugin 6.5.13, Engine 4.10.5 | getestet | `spotbugsMain` und `spotbugsTest` erfolgreich, ein Befund begründet unterdrückt |
| Shadow | 9.6.1 | getestet | Jar mit relokiertem HikariCP und SLF4J |
| HikariCP | 7.1.0 | getestet | SQLite-Test |
| sqlite-jdbc | 3.54.1.0 | getestet | SQLite-Test |
| MariaDB Connector/J | 4.0.0 | konfiguriert | nur Testcontainers-Test, übersprungen |
| Testcontainers | 2.0.5 | konfiguriert | `integrationTest` läuft durch, der Test wird ohne Docker übersprungen |
| PIT (Gradle-Plugin 1.19.0, Engine 1.20.0, junit5-plugin 1.2.3) | siehe Spalte | getestet | `pitest` lief, 39 Mutanten, 12 getötet (31 Prozent) |
| OWASP Dependency-Check | 13.0.0 | konfiguriert | Analyse bricht ohne `NVD_API_KEY` ab |
| Renovate | n/a | vorbereitet | `renovate.json` besteht den Validator, die GitHub-App ist nicht aktiviert |
| Vault API (compileOnly) | 1.7.1 | konfiguriert | wird aufgelöst, noch nicht im Code benutzt |
| Folia-Serverlauf | 1.21.11 Build 14 | vorbereitet | Download und SHA-256 geprüft, Lauf braucht EULA-Zustimmung |
| Claude-Skills (8) und Agents (5) | siehe CLAUDE_SKILLS.md | getestet | `claude -p` listet alle 13 |

Nicht installiert: claude-java-plugins (keine Lizenz), FoliaScheduler (keine Lizenz, siehe Entscheidung), Zavelinski-Skills außer testsmith und secret-scan, Profiler, JMH, Gitleaks, API-Kompatibilitätswerkzeuge. Begründung in GITHUB_RESEARCH.md.

Das Quellcode-Gerüst (Paket `dev.orderhub`) ist ein Platzhalter, um die Werkzeuge gegen echten Code zu prüfen. Paketname, Klassen und Tabellenschema sind nicht abgestimmt und sollten durch das echte Design ersetzt werden.
