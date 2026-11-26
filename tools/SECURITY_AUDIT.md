# Sicherheitsprüfung der Claude-Erweiterungen

Geprüft wurde vor jeder Installation, an geklonten Kopien in einem Arbeitsordner außerhalb des Projekts. Nichts aus den Repositories wurde ausgeführt.

## Vorgehen

1. Eigentümer, Aktivität und LICENSE-Datei des Repositories.
2. Dateiliste: Gibt es Hooks, Skripte, ausführbare Dateien, MCP-Konfigurationen?
3. Suche nach `curl`, `wget`, `eval`, `base64`, `sudo`, `rm -rf`, `.ssh`, `.aws`, `credentials`, API-Schlüsseln und Pipes in die Shell.
4. Suche nach Prompt-Injection-Mustern wie "ignore previous", "disregard", "do not tell", "exfiltrate", "system prompt".
5. Durchsicht der installierten SKILL.md-Dateien auf Tool-Freigaben (`allowed-tools`) und Anweisungen, die Rechte erweitern.
6. Nach der Installation: Suche nach Skripten, Hooks und ausführbaren Dateien in `.claude/`, Liste aller externen URLs.

## Ergebnis je Quelle

decebals/claude-code-java: MIT, keine Hooks, keine MCP-Konfiguration. Skripte im Repository (`setup-project.sh`, `link-skills.sh`, `configure-settings.sh`, `eval-routing.sh` und andere) wurden gelesen und nicht ausgeführt. `eval-routing.sh` liest einen API-Schlüssel aus der Umgebung oder aus `~/.config/anthropic/api-key` und ruft eine Modell-API auf; deshalb nicht verwendet. Die Skills selbst sind Markdown. Keine Injection-Muster.

Zavelinski/testsmith und Zavelinski/secret-scan: MIT, je eine SKILL.md, keine Skripte, keine Hooks, keine Netzwerkbezüge. Keine Injection-Muster. Aus dem Sammel-Repository Zavelinski/claude-code-skills wurden die `install.sh`-Skripte nicht verwendet, weil sie laut README `~/.claude/settings.json` verändern, also außerhalb des Projektordners.

ducpm2303/claude-java-plugins: keine LICENSE-Datei. Hooks vorhanden: Hinweisausgaben per `echo` und ein PreToolUse-Hook, der bei jedem Bash-Aufruf `python3` mit der Eingabe des Werkzeugaufrufs startet und nur warnt. Kein Netzwerkzugriff, kein Zugriff auf Zugangsdaten gefunden. Nicht installiert, Grund ist die fehlende Lizenz.

## Zweite Installation (Minecraft-Skills)

Geklont und je Skill-Ordner geprüft: MrPippi/MJP-Paper-Skills, Jahrome907/minecraft-agent-skills, YueMi-Development/Minecraft-Plugin-Templates, zusätzlich oberflächlich FewPz/minedocs-mcp, kenvandine/FoliaNexa, sickn33/agentic-awesome-skills. Die ausgewählten Skill-Ordner bestehen aus Markdown. Ausnahmen, die nicht übernommen wurden: je ein Shell- und Node-Skript bei minecraft-plugin-dev und minecraft-testing, ein Python-Skript und `allowed-tools: Read, Write, Bash, Glob, Grep` bei java-gradle. Keine Injection-Muster, keine Netzwerkaufrufe, keine Tool-Freigaben in den installierten Dateien. Die Repositories enthalten weitere Skripte (zum Beispiel einen Server-Download in MJP/scripts), die nicht installiert und nicht ausgeführt wurden. Die Prüfung der übrigen Dateien dieser Repositories war nur eine Mustersuche, keine Zeile-für-Zeile-Lektüre.

## Zustand nach der ersten Installation

In `.claude/` liegen 19 Markdown-Dateien und eine JSON-Datei (`settings.json`). Keine Skripte, keine Hooks, keine ausführbaren Dateien. Die einzigen URLs stehen in Literaturlisten der Skill-READMEs. Keine Skill setzt `allowed-tools`.

## Berechtigungen

`.claude/settings.json` ist projektlokal und erlaubt nur Gradle-Aufgaben und lesende Git-Befehle. Verboten sind das Lesen von `.env`, Force-Push und `git reset --hard`. Globale Einstellungen unter `~/.claude` wurden nicht geändert.

Die Agents `paper-architect`, `folia-concurrency-reviewer` und `plugin-security-reviewer` haben nur lesende Werkzeuge. `plugin-test-engineer` und `gradle-build-resolver` dürfen schreiben und Bash ausführen.

## Grenzen der Prüfung

Die Prüfung ist eine Textdurchsicht und Mustersuche. Sie schließt nicht aus, dass eine Skill-Anweisung das Modell zu unerwünschtem Verhalten bringt, das ohne Ausführung nicht sichtbar wird. Deshalb liegen die Skills unverändert im Repository und sind per Git nachvollziehbar. Die Maven-Abhängigkeiten wurden nicht auf Schwachstellen geprüft, weil Dependency-Check ohne NVD-Schlüssel nicht läuft.
