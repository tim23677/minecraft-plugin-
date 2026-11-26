# Werkzeugumgebung von OrderHub

Dieser Ordner dokumentiert, was für die Entwicklung eingerichtet wurde, mit welchem Ergebnis und welchen Einschränkungen. Stand: 2026-11-26.

Status der Werkzeuge in einem Satz: Build, Spotless, SpotBugs, MockBukkit-Tests, SQLite-Test, Shadow-Jar und PIT laufen und wurden ausgeführt. Dependency-Check, Testcontainers und der Folia-Serverlauf sind konfiguriert beziehungsweise vorbereitet, konnten hier aber nicht ausgeführt werden (fehlender NVD-Schlüssel, kein Docker-Daemon, keine EULA-Zustimmung).

Dateien

- INSTALLED_TOOLS.md: Was installiert ist, mit Status je Werkzeug.
- GITHUB_RESEARCH.md: Befunde zu den zehn untersuchten Repositories und Alternativen.
- SECURITY_AUDIT.md: Prüfung der Claude-Erweiterungen vor der Installation.
- LICENSE_AUDIT.md: Lizenzen aller Quellen und Bibliotheken.
- CLAUDE_SKILLS.md: Installierte Skills und Agents, nicht installierte Erweiterungen.
- JAVA_TOOLCHAIN.md: Java, Gradle, Versionen und Abweichungen von der Vorgabe.
- PAPER_FOLIA_TESTING.md: Was MockBukkit kann, was nicht, und die Folia-Testumgebung.
- GRADLE_CONFIGURATION.md: Aufbau von build.gradle.kts und Versionskatalog.
- INSTALLATION_LOG.md: Chronologie mit Fehlern und Korrekturen.
- FINAL_REPORT.md: Abschlussbericht und Bewertung.
- decisions/0001-scheduler.md: Entscheidung zur Scheduler-Abstraktion.
- folia-test/: Skript für den echten Folia-Lauf.
- licenses/: Lizenztexte der kopierten Skills und deren Herkunft.
- mcstage/: Szenario und Skripte für echte Paper- und Folia-Läufe.
- ../.claude/guidelines/: Richtlinien aus okocraft/docs für die Skills.
