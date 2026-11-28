# OrderHub Release-Bericht 2.0.0

Stand: 2026-11-27, Branch `claude/orderhub-2-0-0-release-32pb6v`.

## Ergebnis

Die Version ist nicht 10/10 im Sinn des Master-Prompts. Build und alle ausgeführten Prüfungen sind grün. Mutationsschwellen sind nur teilweise erreicht, mehrere Funktionen fehlen und einige Prüfungen konnten hier nicht laufen. Alles steht unten, mit dem Befehl zum Nachholen.

## Was tatsächlich gelaufen ist

| Prüfung | Befehl | Ergebnis |
|---|---|---|
| Gesamtbuild mit `-Werror`, Spotless, SpotBugs, Tests, Jars, API-Modul, Verkaufs-ZIP | `./gradlew spotlessApply build -x dependencyCheckAnalyze --max-workers=1` | erfolgreich, 1190 Tests, 0 Fehler, 0 übersprungen |
| Integrationstests gegen MariaDB 11.4 | `./gradlew integrationTest` | 63 Tests, 1 übersprungen, 0 Fehler |
| Echte Server Paper 1.21.11 und Folia 1.21.11 mit dem 2.0.0-Jar | `ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh` | 2 bestanden |
| Spielerszenario mit zwei Bots auf Paper (EssentialsX 2.22.0, VaultUnlocked 2.20.3, harter Abbruch und Wiederanlauf) | `node tools/e2e/e2e.js` | 17 von 17 bestanden |
| Lasttest SQLite, 100.000 Aufträge | `./gradlew loadTest` | p50 17 ms, p95 153 ms (Stand Phase 8, im Endstand nicht wiederholt) |
| Lasttest MariaDB, 100.000 Aufträge, zwei simulierte Server | `./gradlew loadTest` | Keyset-Seite p95 8 ms, Textsuche p95 96 ms (Stand Phase 8) |
| Upgrade von einer echten 1.1.0-Datenbank | `UpgradeFrom110Test` | bestanden |
| API-Kompatibilität gegen 1.1.0 | japicmp im Build | bestanden |
| Mutationstest je Paket | `./gradlew pitest -PpitTarget='dev.orderhub.<paket>.*'` | tx 85,1 %, item 94,2 %, economy 76,6 %, storage 82,7 % |

Zu PIT: `economy` enthält `economy.vault` (31 Mutanten ohne Abdeckung, der Vault-Adapter braucht ein echtes Vault). Ohne dieses Unterpaket liegt `economy` bei 36 von 47 abgedeckten und 36 getöteten Mutanten, also über der Schwelle. `storage` liegt 2,3 Punkte unter 85 %. `flow`, `command`, `service` und die übrigen Pakete wurden nicht gemessen. Ein Lauf über alle Pakete überschreitet das Zeitlimit (mehr als eine Stunde, Zeitüberschreitungen bei einzelnen Mutanten), deshalb gibt es keine Gradle-Prüfung der Schwellen.

## Reviews

Sicherheits-, Folia-, Architektur- und Test-Review liefen. Behoben wurden unter anderem Geheimnisse im Support-Dump (IPv6, URL-Parameter), Dateirechte und atomares Schreiben bei Konfiguration und Sicherung, Berechtigung für `dump`, Race im Marktplatzmenü (Generationszähler), Abschalten des Transaktionslogs, Cooldown-Freigabe, Doppelstart des Auszahlungsworkers. 63 Tests kamen aus dem Test-Review hinzu.

Danach behoben: Ratenbegrenzung der Textsuche (3 Suchen in 5 Sekunden je Spieler), eine Abfrage statt N+1 bei `/order sell`, Dump liest nur die letzten 2 MB des Serverlogs, beanspruchte Login-Hinweise werden freigegeben, wenn der Spieler im selben Moment geht, der Platzhalter `open_orders` zählt ohne Zeilensperre.

Nicht behoben und begründet:
- SQL-Kommentar in V1 (Migrationen dürfen wegen der Prüfsumme nicht geändert werden)
- Datenbankzugriff der Rückgabewarteschlange beim Abschalten
- `economy.async-safe` auf Folia
- größere Umbauten aus dem Architektur-Review

## Verbesserungen nach dem ersten Bericht

Fünfzehn Änderungen, jede mit Tests: eine Abfrage für die Optionen aller Kandidaten in `/order sell`, Suchdrosselung, Dump-Log nur als Ende der Datei, Freigabe unzugestellter Hinweise, Auto-Abholung (das Flag wird jetzt ausgewertet), Auftragsoptionen per Befehl (`durability=`, `enchants`, `autoclaim` bei `/order create` und `/order template save`), Optionen in Vorlagen gespeichert und beim Benutzen angewendet, Prüfung des Ton-Schlüssels in der Konfiguration, `/order info <id>`, `/order sell all`, die Platzhalter `market_open_orders` und `claims_waiting` (Auflösung ohne PlaceholderAPI testbar), Zählung offener Aufträge ohne Sperre, Vorschlag bei falsch getipptem Unterbefehl, vollständige Hilfe, Vorlagen als Menü (Linksklick benutzt, Rechtsklick löscht).

Dazu `docs/market-research.md` (Quellen, nur Listentexte gelesen) und `docs/media-checklist.md`.

## Zweite Verbesserungsrunde und Simulation

Auf einem echten Paper-Server (1.21.11) mit EssentialsX, VaultUnlocked und LuckPerms: 166 von 166 Prüfungen mit SQLite, 166 von 166 mit MariaDB 11.4, 17 von 17 beim harten Abbruch mit Recovery. Einzelheiten stehen in `SIMULATION_REPORT.md`, wiederholbar mit `sim/run.sh`.

Neu: Preisfilter im Markt, Drosselung schneller Abfragen, `/orderadmin player` und `/orderadmin audit`, Systemzeile in `/orderadmin health`, Login-Hinweis für bald ablaufende Aufträge, Marktplatzhalter, Tab-Completion für Admin-Befehle. Behoben: Die Menge im Chat wird sofort geprüft.

## Umgesetzt

Marktplatz mit Suche, Kategorien, Sortierung und Keyset-Seiten; `/order sell`; Auftragsoptionen mit Toleranz-Abgleich; Komponenten-Voreinstellungen; Vault-Timeout mit Latenzmessung; `/order template` (speichern, auflisten, benutzen, löschen, 10 je Spieler); `/order renew`; Menüs für Statistik, Verlauf, Favoriten, Analyse; Login-Zusammenfassung und serverübergreifende Hinweise; neun Sprachen mit Schlüssel- und Platzhaltertest; Transaktionslog mit Rotation; erweitertes `health`; automatische SQLite-Sicherung; bStats (ohne Plugin-Id inaktiv); `/orderadmin dump`; API als eigenes Modul; Migration V5; Mehrserver-Tests; Lasttests; reproduzierbare Jars, SBOM, Variante mit eingebetteten Treibern (ADR 0004, 0005); Verkaufs-ZIP mit Inhaltsprüfung; Dokumentation.

## Nicht umgesetzt

| Punkt | Stand |
|---|---|
| Erstellmenü mit allen Optionen, Gebühr für Auto-Verlängerung, Review-Menü | nicht umgesetzt. Optionen gehen per Befehl, Verlängerung per `/order renew`, der Review geht per Befehl |
| Import von Konkurrenzplugins | nicht umgesetzt |
| Aufteilung großer Klassen (Phase 6) | nicht durchgeführt |
| PIT-Schwellen als Gradle-Prüfung | nicht eingerichtet, siehe oben |
| Update-Prüfung über Plattform-API | bleibt bei GitHub-Releases, Standard aus |
| Offline-MariaDB | keine Lösung, der Treiber steht unter LGPL (ADR 0005) |
| Screenshots und GIFs selbst | nicht aufgenommen, die Liste mit Testserver-Aufbau steht in `docs/media-checklist.md` |

## Nicht ausgeführt

| Prüfung | Grund | Befehl zum Nachholen |
|---|---|---|
| PIT für `flow`, `command`, `service`, übrige Pakete; `storage` unter 85 % | Laufzeit je Lauf über eine Stunde | `./gradlew pitest -PpitTarget='dev.orderhub.flow.*' --max-workers=1` |
| `OrderHub-2.0.0-bundled.jar` auf einem Server | nicht gestartet | Jar statt der normalen Datei einlegen, Server ohne Internet starten |
| `dependencyCheckAnalyze` | `NVD_API_KEY` fehlt | `NVD_API_KEY=… ./gradlew dependencyCheckAnalyze` |
| Folia mit Wirtschaftsplugin, Bot-Szenario auf Folia | nicht aufgesetzt | `node tools/e2e/e2e.js <server> <result.json>` gegen einen Folia-Server |
| Lasttest im Endstand | nicht wiederholt | `./gradlew loadTest` |
| GitHub-Workflows | kein Runner | Tag `v2.0.0` pushen |
| Geyser und Bedrock-Client | nicht verfügbar | Testserver mit Geyser und Bedrock-Client |

## Restrisiken

- Die sieben neuen Sprachen wurden nicht von Muttersprachlern geprüft.
- bStats liefert keine Daten, bis eine Plugin-Id registriert und in `MetricsSetup` eingetragen ist.
- Der Bot-Test (17 von 17) lief auf Paper, nicht auf Folia.
- Vault bietet keine Transaktions-Ids. Ein Absturz genau zwischen Auszahlung und Speichern des Ergebnisses endet in MANUAL_REVIEW und braucht einen Administrator.
- Die Verkaufsplattformen haben eigene Regeln (`docs/marketplace-rules.md`). Spigot Premium nimmt keine neuen Autoren, Lizenzsysteme sind dort verboten.
