# Installationsprotokoll

Alle Zeiten 2026-11-26.

1. Analyse: leeres Repository (eine README-Zeile), Java 21.0.13.1, Gradle 8.14.3, Docker-CLI ohne Daemon, keine `.claude/` im Projekt.
2. Erreichbarkeit: Maven Central, Gradle Plugin Portal, Paper-Repository, JitPack, fill.papermc.io, Mojang erreichbar. GitHub-API liefert 403 (Zugriff auf Fremd-Repositories nicht freigegeben), `git clone` öffentlicher Repositories funktioniert.
3. Recherche der drei Claude-Repositories über die GitHub-Seiten, Klon von decebals/claude-code-java, ducpm2303/claude-java-plugins, Zavelinski/testsmith und Zavelinski/secret-scan in einen Arbeitsordner außerhalb des Projekts. Hooks, Skripte und Lizenzen geprüft.
4. Versionsrecherche über Maven-Metadaten. Befund: Paper 26.x verlangt Java 25, daher Paper API 1.21.11.
5. Erste Gradle-Ausführung: HTTP 429 von Maven Central bei Plugin-Downloads. Ursache: Ratenbegrenzung. Korrektur: `gradle.properties` mit zwei Workern, Wiederholung, danach erfolgreich.
6. Konfigurationsfehler: Shadow 9.6.1 unter Gradle 8.14.3 bricht beim Konfigurieren ab. Korrektur: Wrapper auf 9.9.2 (aktuelle stabile Version laut services.gradle.org), danach Kompilierung erfolgreich.
7. Testlauf: 9 von 10 Tests schlugen fehl ("Cannot subclass primitive, array or final types"). Ursache: Hauptklasse war `final`, MockBukkit unterklassifiziert sie. Korrektur: Klasse nicht final, im Code kommentiert. Danach 10 von 10 bestanden.
8. SpotBugs meldete `EI_EXPOSE_REP` in `OrderHubMenu.getInventory()`. Ursache: Rückgabe des internen Inventars, vom Interface vorgeschrieben. Korrektur: gezielte `@SuppressFBWarnings`-Annotation mit Begründung an dieser Methode, keine globale Abschaltung.
9. Dependency-Check scheiterte mit "Invalid API Key, length of 0". Erste Ursache: leere Umgebungsvariable wurde als Schlüssel gesetzt. Korrektur: Schlüssel nur setzen, wenn vorhanden. Zweiter Lauf scheitert weiterhin, weil Version 13 einen Schlüssel verlangt. Nicht lösbar ohne Schlüssel, nicht umgangen.
10. PIT lief erfolgreich (39 Mutanten, 12 getötet).
11. Update auf SpotBugs 4.10.5 und AssertJ 3.27.7. Vollständiger `build` mit Spotless, SpotBugs, Tests und Shadow-Jar erfolgreich.
12. Skills und Agents installiert, Erkennung mit `claude -p` bestätigt.
13. `renovate.json` mit `renovate-config-validator` geprüft (als globale Konfiguration validiert).
14. Folia: Download und SHA-256 bestätigt, Lauf wegen fehlender EULA-Zustimmung nicht ausgeführt.
15. Zweite Suche auf GitHub (Repository- und Code-Suche nach Minecraft, Paper, Folia, Claude-Skills, MCP). Eine Durchsuchung ganz GitHubs ist nicht möglich, die Suche ist begrenzt auf diese Begriffe. Elf Treffer, sechs davon geklont und geprüft.
16. 13 Skills aus MJP-Paper-Skills, YueMi und Jahrome übernommen (siehe CLAUDE_SKILLS.md). Erster Kopierversuch für die zwei Jahrome-Skills schlug fehl (Verzeichnisse nicht angelegt), danach korrigiert.
17. Dritte Suche (Testen, Simulieren, Coding-Hilfen). 40 Treffer gesichtet, sechs geklont. GitHub-API für Fremd-Repositories blockiert (HTTP 403), `git clone` funktioniert.
18. okocraft-Skills und Richtlinien, bot-qa (nur Markdown) kopiert. Acht offizielle Plugins per `claude plugin install --scope project` eingetragen.
19. mcstage: erster Build scheiterte mit HTTP 429 von Maven Central, Wiederholung erfolgreich, Wiederholungsschleife in setup.sh ergänzt.
20. Erster mcstage-Lauf (Paper und Folia, EULA vom Nutzer erteilt): 2 von 2 fehlgeschlagen. Ursache war der Schritt `enabled:`, der bei Plugins vor "Done" nicht greift. Szenario auf `plugins`-Abfrage umgestellt, `level-type: flat` entfernt. Zweiter Lauf: 2 von 2 bestanden in 35,3 s.
