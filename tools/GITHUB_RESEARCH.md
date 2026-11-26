# GitHub-Recherche

Abgerufen am 2026-11-26 über die öffentlichen GitHub-Seiten, `git clone --depth 1`, `git ls-remote` und die Maven-Metadaten. Die GitHub-API war aus der Umgebung nicht erreichbar (HTTP 403), deshalb fehlen Issue-Zahlen und Security-Advisories. Sicherheitsprobleme der Projekte selbst wurden nicht systematisch geprüft, sondern nur die Dateien durchgesehen, die ich installiert habe.

Zwei der vorgegebenen URLs existieren so nicht. `OWASP/dependency-check` ist nicht erreichbar, das Projekt liegt unter `dependency-check/DependencyCheck`. `spotless/spotless` existiert nicht, das Projekt liegt unter `diffplug/spotless`. Untersucht wurden die tatsächlichen Repositories.

## 1. ducpm2303/claude-java-plugins

Letzter Commit 2026-04-06, 80 Commits, 17 Sterne, Release v2.2.2 (6 Releases), zu 100 Prozent Shell und Markdown. Drei Plugins im Marketplace-Format: java-core (14 Skills, Agents java-architect und java-build-resolver), java-spring (9 Skills), java-quality (3 Skills, Agents für Security, Performance und Tests). Installation laut README über `/plugin marketplace add ducpm2303/claude-java-plugins`. Wirbt mit Java 8 bis 21. Die Inhalte sind auf Maven und Spring Boot ausgerichtet. Im Repository liegt keine LICENSE-Datei. Hooks: SessionStart und PostToolUse geben nur Hinweistexte aus; ein PreToolUse-Hook startet bei jedem Bash-Aufruf ein python3-Skript, das die Variable `CLAUDE_TOOL_INPUT` auswertet und nur warnt. Kein Netzwerkzugriff gefunden. Eignung für OrderHub: gering bis mittel. Entscheidung: nicht installiert, weil ohne Lizenz weder Kopieren noch Weitergabe in ein kommerzielles Repository gedeckt ist. Die benötigten Rollen deckt ein eigener Satz Agents ab.

## 2. decebals/claude-code-java

Lizenz MIT. Letzter Commit 2026-10-30, 45 Commits, 779 Sterne, 153 Forks, Release v1.0.0 vom 2026-08-28. 18 Skills nach der offenen Agent-Skills-Spezifikation, Shell-Skripte für Setup und Routing-Test, Templates. Keine Hooks. Installation laut README `npx skills@latest add decebals/claude-code-java` oder manuelles Kopieren. Ich habe nicht das npx-Paket ausgeführt, sondern sechs Skill-Ordner kopiert (siehe CLAUDE_SKILLS.md). Die Skripte `eval-routing.sh` lesen `ANTHROPIC_API_KEY` oder `~/.config/anthropic/api-key`; sie wurden nicht ausgeführt. Eignung: hoch für Review, Tests, Nebenläufigkeit, Sicherheit und Architektur. Spring-, JPA- und Git-Skills sind für OrderHub nicht relevant.

## 3. Zavelinski/claude-code-skills

Lizenz MIT. Marketplace mit 35 Plugins (die Repository-Beschreibung nennt 20), letzter Commit 2026-06-30, 15 Commits, 0 Sterne, ein Einzelautor, Commits mit Claude als Mitautor. Der CI-Status des letzten Commits steht auf Fehler. Jedes Plugin ist ein eigenes Repository mit `install.sh`, das laut README `~/.claude/settings.json` verändert; diese Skripte wurden nicht ausgeführt. Aus dem Katalog sind testsmith (Adversarial Test Generator) und secret-scan (Secret Scanning) relevant. Beide Repositories klonte ich, beide bestehen nur aus einer SKILL.md ohne Hooks, ohne Skripte und ohne Netzwerkbezug. Installiert: diese zwei. Nicht installiert: die übrigen 33, darunter godmode, salve und safety-net, weil sie Verhalten oder Berechtigungen des Agenten verändern und für OrderHub keinen konkreten Nutzen belegen.

## 4. MockBukkit/MockBukkit

Lizenz MIT. Letzter Commit 2026-11-25, aktuelle Version 4.126.0. Es gibt getrennte Artefakte je Minecraft-Linie: `mockbukkit-v1.21` (4.116.3, 2026-08-19), `mockbukkit-v26.1.2`, `v26.2`, `v26.3`. Das v1.21-Artefakt zieht JUnit Jupiter 6.0.3 als Laufzeitabhängigkeit, daher läuft OrderHub auf JUnit 6 statt 5. Die Hauptklasse des Plugins darf nicht final sein. Eingesetzt und getestet.

## 5. CJCrafter/FoliaScheduler

Letzter Commit 2026-05-27, Tag v0.8.0, README nennt Folia 1.20+ und Spigot/Paper 1.12.2+, Java 1.8+. Im Repository-Wurzelverzeichnis liegt keine LICENSE-Datei. Eignung: nicht nötig, siehe decisions/0001-scheduler.md. Nicht installiert.

## 6. spotbugs/spotbugs

Lizenz LGPL 2.1. Letzter Commit 2026-11-25. Engine 4.10.5 auf Maven Central, Gradle-Plugin 6.5.13. Läuft mit Java 21 und Gradle 9.9.2. Eingesetzt und getestet.

## 7. dependency-check/DependencyCheck

Lizenz Apache 2.0. Letzter Commit 2026-11-22, Release v13.0.0 (Gradle-Plugin 13.0.0 vom 2026-08-03). Version 13 verlangt einen NVD-API-Schlüssel. Konfiguriert, nicht ausgeführt.

## 8. diffplug/spotless

Lizenz Apache 2.0. Letzter Commit 2026-11-23, Gradle-Plugin 8.12.0 (2026-11-21). Eingesetzt und getestet.

## 9. testcontainers/testcontainers-java

Lizenz MIT. Letzter Commit 2026-11-19, Version 2.0.5. In Version 2 heißen die Artefakte `testcontainers-junit-jupiter` und `testcontainers-mariadb`, die Klasse `org.testcontainers.mariadb.MariaDBContainer`. Konfiguriert; der Test wird ohne Docker-Daemon übersprungen.

## 10. renovatebot/renovate

Lizenz AGPL 3.0. Letzter Commit 2026-11-26. Wird nicht in das Projekt eingebunden, sondern als gehostete GitHub-App oder Selbsthosting betrieben; im Repository liegt nur `renovate.json`. Der AGPL-Text betrifft das Betreiben eines modifizierten Renovate-Dienstes, nicht die Konfigurationsdatei.

## Weitere Werkzeuge

PIT Mutation Testing (Apache 2.0, Version 1.20.0, Gradle-Plugin 1.19.0): eingesetzt, bringt Nutzen, weil es misst, ob die Tests Fehler fangen. Ergebnis für das Gerüst: 31 Prozent Mutanten getötet, Teststärke 48 Prozent. Kostenpflichtige Erweiterungen (Arcmutate) wurden nicht verwendet.

Nicht installiert, weil heute ohne konkreten Anlass: JMH und Java Flight Recorder für Profiling (JFR ist im JDK enthalten und braucht keine Installation), jcstress für Nebenläufigkeitstests (sinnvoll erst bei eigenen lock-freien Strukturen), Gitleaks für Secret Scanning in CI (die Skill secret-scan deckt die lokale Prüfung ab), japicmp für API-Kompatibilität (sinnvoll ab der ersten veröffentlichten Version), Flyway für Migrationen (die aktuelle Migration ist ein Platzhalter, Entscheidung gehört in das Datenmodell-Design), ein Heap-Analysewerkzeug für Speicherlecks (Eclipse MAT ist eine Desktop-Anwendung).
