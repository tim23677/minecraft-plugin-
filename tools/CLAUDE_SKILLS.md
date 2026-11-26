# Claude-Code-Skills und Agents

Alle Erweiterungen liegen projektlokal in `.claude/`. Unterschieden wird nach Art: Skills sind Markdown-Anleitungen, die Claude bei passender Aufgabe lädt. Agents sind Unterassistenten mit eigenem Prompt und eigener Werkzeugliste. Plugins, MCP-Server und Hooks sind nicht installiert.

## Installierte Skills (8, unverändert kopiert)

Aus decebals/claude-code-java (MIT): java-code-review, concurrency-review, performance-smell-detection, test-quality, security-audit, architecture-review. Aus Zavelinski (MIT): testsmith für Testgenerierung mit Mutationszyklus, secret-scan für die Prüfung vor Commit und Push. Commit-Stände stehen in tools/licenses/SOURCES.md.

Die Skills sind auf Maven und teilweise auf Spring ausgerichtet. Für Gradle- und Paper-Besonderheiten greifen die eigenen Agents.

## Zweite Installation: Minecraft- und Paper-Skills (13)

Alle MIT, Herkunft und Commits in tools/licenses/SOURCES.md.

Aus MrPippi/MJP-Paper-Skills (unverändert): paper-economy-ledger, paper-sqlite-repository, paper-chest-gui, paper-menu-type-gui, paper-brigadier-command, paper-softdepend-hook, paper-service-api, paper-config-lang. Die Beschreibungen sind teilweise auf Chinesisch, die Inhalte zweisprachig.

Aus YueMi-Development/Minecraft-Plugin-Templates (unverändert): minecraft-paper-folia-plugin, minecraft-plugin-manifest, minecraft-bstats.

Aus Jahrome907/minecraft-agent-skills: minecraft-plugin-dev, minecraft-testing. Nur die Markdown-Dateien. Die zugehörigen Prüfskripte (`validate-plugin-layout.sh`, `validate-test-layout.sh`) wurden nicht übernommen, die SKILL.md verweist aber darauf. Diese Verweise laufen ins Leere.

Überschneidungen: minecraft-plugin-dev und minecraft-paper-folia-plugin behandeln beide Scheduler und Folia. Die Skills geben teils feste Entwurfsentscheidungen vor (zum Beispiel SQLite mit einer Verbindung und Write-behind, Beträge als long). Sie sind Vorschläge. Wo sie `CLAUDE.md` oder dem Entwurf von OrderHub widersprechen, gilt `CLAUDE.md`. Alle MJP-Skills zielen auf Paper 1.21.11 und 26.x. Ob jede Codebeispiel-Zeile unter Java 21 kompiliert, wurde nicht geprüft.

Nicht installiert, obwohl in der Suche gefunden: FewPz/minedocs-mcp (keine Lizenz), kenvandine/FoliaNexa (GPL 3), jedbillyb/claude-bridge (keine Lizenz, Server-Plugin), sickn33/agentic-awesome-skills (zu breit, Inhalt durch die übrigen abgedeckt), java-gradle und java-concurrency aus YueMi (Skripte mit Bash-Freigabe, Überschneidung mit vorhandenen Skills und Agents).

## Eigene Agents (5)

paper-architect (nur lesend): Schichten, Scheduler-Regel, Storage-Grenzen.
folia-concurrency-reviewer (nur lesend): Thread-Besitz, geteilter Zustand, Check-then-act, blockierende I/O.
plugin-security-reviewer (nur lesend): SQL, Berechtigungen, GUI-Exploits, Wirtschaft, Zugangsdaten.
plugin-test-engineer (schreibend): MockBukkit, Testcontainers, PIT, testsmith.
gradle-build-resolver (schreibend): Buildfehler mit minimalen Änderungen, ohne Prüfungen abzuschalten.

Zuordnung zur Prioritätenliste der Aufgabe: Java Architect (paper-architect), Code Reviewer (java-code-review), Security Reviewer (plugin-security-reviewer, security-audit), Test Engineer (plugin-test-engineer, test-quality), Concurrency Reviewer (folia-concurrency-reviewer, concurrency-review), Performance Reviewer (performance-smell-detection), Build Resolver (gradle-build-resolver), Architecture Reviewer (architecture-review), Adversarial Test Generator (testsmith), Test Quality Reviewer (test-quality).

Überschneidungen: Die Agents und Skills ergänzen sich bewusst (Agent für den Kontext des Projekts, Skill für die Checkliste). Widersprüche zwischen ihnen sind nicht bekannt; falls Claude sie sichtbar vermischt, gilt `CLAUDE.md`.

## Prüfung

`claude -p` im Projektordner listete alle acht Skills und alle fünf Agents. Das beweist, dass sie erkannt werden, nicht dass jede Skill bei jeder Aufgabe passend ausgelöst wird. Die Skills wurden nicht gegen echte OrderHub-Aufgaben getestet, weil es noch keinen Produktivcode gibt.

Hinweis: Die Berechtigungen in `.claude/settings.json` greifen erst, wenn der Ordner in Claude Code als vertrauenswürdig bestätigt wurde. Das ist eine Handlung des Nutzers.

## Nicht installiert

claude-java-plugins (keine Lizenz), die 33 übrigen Zavelinski-Plugins, alle Hooks, alle MCP-Server. Begründung in GITHUB_RESEARCH.md und SECURITY_AUDIT.md.
