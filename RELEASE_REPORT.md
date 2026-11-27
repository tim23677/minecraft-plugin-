# OrderHub Release-Bericht 2.0.0

Stand: 2026-11-27, Branch `claude/orderhub-2-0-0-release-32pb6v`.

## Ergebnis

Die Version ist nicht 10/10 im Sinn des Master-Prompts. Build und alle ausgeführten Prüfungen sind grün. Mutationsschwellen sind nur teilweise erreicht, mehrere Funktionen fehlen und einige Prüfungen konnten hier nicht laufen. Alles steht unten, mit dem Befehl zum Nachholen.

## Was tatsächlich gelaufen ist

| Prüfung | Befehl | Ergebnis |
|---|---|---|
| Gesamtbuild mit `-Werror`, Spotless, SpotBugs, Tests, Jars, API-Modul, Verkaufs-ZIP | `./gradlew spotlessApply build -x dependencyCheckAnalyze --max-workers=1` | erfolgreich, 1107 Tests, 0 Fehler |
| Integrationstests gegen MariaDB 11.4 | `./gradlew integrationTest` | 60 Tests, 1 übersprungen, 0 Fehler |
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

Nicht behoben und begründet:
- SQL-Kommentar in V1 (Migrationen dürfen wegen der Prüfsumme nicht geändert werden)
- Ratenbegrenzung der LIKE-Suche und N+1-Abfragen bei `/order sell`
- `readAllLines` von `latest.log` im Dump
- Login-Hinweise gehen bei sehr schnellem Wiederbeitritt verloren
- Datenbankzugriff der Rückgabewarteschlange beim Abschalten
- `economy.async-safe` auf Folia
- größere Umbauten aus dem Architektur-Review

## Umgesetzt

Marktplatz mit Suche, Kategorien, Sortierung und Keyset-Seiten; `/order sell`; Auftragsoptionen mit Toleranz-Abgleich; Komponenten-Voreinstellungen; Vault-Timeout mit Latenzmessung; `/order template` (speichern, auflisten, benutzen, löschen, 10 je Spieler); `/order renew`; Menüs für Statistik, Verlauf, Favoriten, Analyse; Login-Zusammenfassung und serverübergreifende Hinweise; neun Sprachen mit Schlüssel- und Platzhaltertest; Transaktionslog mit Rotation; erweitertes `health`; automatische SQLite-Sicherung; bStats (ohne Plugin-Id inaktiv); `/orderadmin dump`; API als eigenes Modul; Migration V5; Mehrserver-Tests; Lasttests; reproduzierbare Jars, SBOM, Variante mit eingebetteten Treibern (ADR 0004, 0005); Verkaufs-ZIP mit Inhaltsprüfung; Dokumentation.

## Nicht umgesetzt

| Punkt | Stand |
|---|---|
| Vorlagen im Menü, Erstellmenü mit allen Optionen, Massenverkauf, Gebühr für Auto-Verlängerung, Review-Menü | nicht umgesetzt (Vorlagen und Verlängerung gibt es als Befehle) |
| Auto-Abholung | Flag wird gespeichert, aber nirgends ausgewertet |
| Import von Konkurrenzplugins | nicht umgesetzt |
| Aufteilung großer Klassen (Phase 6) | nicht durchgeführt |
| PIT-Schwellen als Gradle-Prüfung | nicht eingerichtet, siehe oben |
| Update-Prüfung über Plattform-API | bleibt bei GitHub-Releases, Standard aus |
| Offline-MariaDB | keine Lösung, der Treiber steht unter LGPL (ADR 0005) |
| Screenshot- und GIF-Liste, `docs/market-research.md` mit Quellen | nicht geschrieben |

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
