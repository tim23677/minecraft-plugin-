# Abschlussbericht

Stand 2026-11-26. Branch `claude/orderhub-toolkit-setup-54f5ua`.

## Untersuchte Repositories

Zehn vorgegebene (zwei mit korrigierter URL) plus PIT und die Sub-Repositories testsmith und secret-scan. Einzelbefunde in GITHUB_RESEARCH.md.

## Installierte Claude-Code-Erweiterungen

Acht Skills (java-code-review, concurrency-review, performance-smell-detection, test-quality, security-audit, architecture-review, testsmith, secret-scan) und fünf eigene Agents (paper-architect, folia-concurrency-reviewer, plugin-security-reviewer, plugin-test-engineer, gradle-build-resolver). Keine Plugins, Hooks oder MCP-Server. `claude -p` erkennt alle 13.

## Installierte Java- und Gradle-Werkzeuge

Gradle 9.9.2, Spotless 8.12.0, SpotBugs 4.10.5 (Plugin 6.5.13), Shadow 9.6.1, Dependency-Check 13.0.0, PIT 1.20.0 (Plugin 1.19.0), Testcontainers 2.0.5, MockBukkit 4.116.3, JUnit 6.0.3, AssertJ 3.27.7.

Nachtrag: Später kamen 11 Skills (okocraft, bot-qa, Paper-Skills, siehe CLAUDE_SKILLS.md), sechs Richtlinien und acht offizielle Plugins hinzu. Gesamt 24 Skills, 5 Agents, 8 Plugins.

Nachtrag Serverlauf: Der Folia-Punkt unter "Fehlgeschlagen oder nicht ausgeführt" ist überholt. Paper und Folia 1.21.11 wurden mit mcstage real gestartet und bestanden (PAPER_FOLIA_TESTING.md). Offen bleibt, dass das Gerüst keine Tasks startet und Spielerinteraktion nicht getestet wurde. Dependency-Check und Testcontainers sind weiterhin nicht ausgeführt.

## Verwendete Bibliotheken

Paper API 1.21.11, Vault API 1.7.1 (nur Compile-Zeit), HikariCP 7.1.0, SLF4J 2.0.17, sqlite-jdbc 3.54.1.0, MariaDB Connector/J 4.0.0.

## Geänderte und neue Dateien

Vorhandene Datei: `README.md` um einen Verweis auf CLAUDE.md und tools/README.md ergänzt, der bestehende Inhalt ist unverändert. Neu: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle-Wrapper, `.gitignore`, `renovate.json`, `CLAUDE.md`, `.claude/` (Skills, Agents, settings.json), `src/` (Platzhalter-Gerüst und Tests), `tools/` (diese Dokumentation, Folia-Skript, Lizenztexte).

## Lizenzen

Siehe LICENSE_AUDIT.md. Offen: sqlite-jdbc, SLF4J, pitest-junit5-plugin, Gradle und Shadow ohne maschinelle Bestätigung. Prüfbedürftig: LGPL des MariaDB-Treibers im Fat-Jar.

## Erfolgreiche Prüfungen

`./gradlew build` (Kompilierung mit -Werror, Spotless, SpotBugs auf Main und Test, 10 Tests, Shadow-Jar). 10 von 10 Tests bestanden (9 mit MockBukkit, 1 mit echter SQLite-Datei). `pitest` lief. `renovate.json` besteht den Validator. Skills und Agents werden von Claude Code erkannt. Download und Prüfsumme von Folia bestätigt.

## Fehlgeschlagen oder nicht ausgeführt

Dependency-Check: bricht ohne `NVD_API_KEY` ab, nicht umgangen. Testcontainers/MariaDB: Test übersprungen, kein Docker-Daemon. Folia-Serverlauf: nicht ausgeführt, EULA-Zustimmung fehlt. Vault-Integration: nicht getestet, nicht im Code benutzt.

Während der Arbeit behobene Fehler: HTTP 429 von Maven Central, Shadow unter Gradle 8, finale Hauptklasse unter MockBukkit, SpotBugs-Befund, leerer NVD-Schlüssel (Details in INSTALLATION_LOG.md).

## Nicht installiert

claude-java-plugins (keine Lizenz), FoliaScheduler (keine Lizenz, nicht nötig), 33 weitere Zavelinski-Plugins, JMH, jcstress, Gitleaks, japicmp, Flyway, Heap-Analysewerkzeug.

## Bekannte Einschränkungen

JUnit 6 statt 5 und Gradle 9 statt 8 wegen der Abhängigkeiten. Paper API ist ein Snapshot. Das MockBukkit-Artefakt für 1.21 wird laut Versionsliste nicht mehr so aktiv gepflegt wie die 26.x-Linie (jüngste v1.21-Version vom 2026-08-19, v26.3 vom 2026-11-25). Die Folia-Pfade sind ohne echten Serverlauf nicht abgedeckt. Das Quellgerüst ist ein Platzhalter. Die Berechtigungen in `.claude/settings.json` gelten erst nach der Vertrauensbestätigung des Ordners in Claude Code. PIT meldet nur 31 Prozent getötete Mutanten, was beim Gerüst erwartbar ist und kein Qualitätsurteil über OrderHub darstellt.

## Empfehlungen

1. Einen NVD-API-Schlüssel beantragen (kostenlos) und als CI-Secret `NVD_API_KEY` hinterlegen, dann `dependencyCheckAnalyze` ausführen.
2. Auf einem Rechner mit Docker `./gradlew integrationTest` ausführen.
3. Einmal `ACCEPT_MINECRAFT_EULA=true tools/folia-test/run-folia-smoke.sh` ausführen, nachdem die EULA gelesen wurde, und das Skript anpassen.
4. Entscheiden, ob 1.21.x (Java 21) oder 26.x (Java 25) das Ziel ist. Das ist die größte offene Weichenstellung.
5. Lizenzfrage für den MariaDB-Treiber im Fat-Jar klären, ehe das Plugin verkauft wird.
6. Die Platzhalter in `src/` durch das echte Design ersetzen und das Paket `dev.orderhub` festlegen.
7. Renovate als GitHub-App für das Repository aktivieren.
8. CI mit GitHub Actions ergänzen (build, integrationTest, dependencyCheck). Das wurde nicht angelegt, weil es Zugangsdaten und Entscheidungen zur Plattform voraussetzt.

## Bewertung

Die Umgebung ist bereit für die Entwicklung der Plugin-Logik: Kompilierung, Formatierung, statische Analyse, Unit- und MockBukkit-Tests, Mutationstests und das Bauen des Release-Jars funktionieren und wurden ausgeführt. Sie ist nicht bereit, Folia-Tauglichkeit, MariaDB-Verhalten und bekannte Schwachstellen in Abhängigkeiten zu belegen. Diese drei Prüfungen sind vorbereitet und brauchen je eine Handlung von dir (EULA, Docker, NVD-Schlüssel). Bis dahin sollte keine Aussage "läuft auf Folia" oder "MariaDB getestet" über das Plugin gemacht werden.
