# OrderHub Release-Bericht 2.0.0

Stand: 2026-11-27, Branch `claude/orderhub-2-0-0-release-32pb6v`.

## Ergebnis

Die Version ist nicht 10/10 im Sinn des Master-Prompts. Der Build ist grün, aber mehrere geforderte Nachweise wurden nicht erbracht und mehrere Funktionen sind nicht umgesetzt. Beides steht unten, jeweils mit dem Befehl, der es nachholt.

## Was tatsächlich gelaufen ist

| Prüfung | Befehl | Ergebnis |
|---|---|---|
| Gesamtbuild mit `-Werror`, Spotless, SpotBugs, Tests, Jars, API-Modul, Verkaufs-ZIP mit Inhaltsprüfung | `./gradlew spotlessApply build -x dependencyCheckAnalyze --max-workers=1` | erfolgreich, 1029 Tests, 0 Fehler, 0 übersprungen |
| Integrationstests gegen MariaDB 11.4 (Testcontainers) | `./gradlew integrationTest` in den Phasen 2, 4 | in den Phasenläufen bestanden (Logs p2it, p4it), im letzten Gesamtstand nicht wiederholt |
| Lasttest SQLite, 100.000 Aufträge | `./gradlew loadTest` | p50 17 ms, p95 153 ms, max 1.782 ms |
| Lasttest MariaDB 11.4, 100.000 Aufträge, zwei simulierte Server | `./gradlew loadTest` | Keyset-Seite p50 4 ms, p95 8 ms, Textsuche p95 96 ms |
| Upgrade von einer echten 1.1.0-Datenbank | `UpgradeFrom110Test` (Teil von `test`) | bestanden |
| API-Kompatibilität gegen 1.1.0 | japicmp im Build | bestanden |

Der erste Fehlschlag, den das Build-Log zeigt (`NoSuchFileException … in-progress-results-generic.bin`), stammt von einem zweiten, gleichzeitig laufenden Gradle-Aufruf im selben Verzeichnis. Der letzte Lauf allein ist erfolgreich.

## Umgesetzt

Marktplatz mit Suche, Kategorien, Sortierung und Keyset-Seiten; `/order sell`; Auftragsoptionen (Mindest-Haltbarkeit, Verzauberungen mindestens) mit Toleranz-Abgleich; Komponenten-Voreinstellungen; Vault-Timeout mit Latenzmessung; Menüs für Statistik, Verlauf, Favoriten, Analyse; Login-Zusammenfassung und serverübergreifende Hinweise; neun Sprachen mit Schlüssel- und Platzhaltertest; Transaktionslog mit Rotation; erweitertes `health`; automatische SQLite-Sicherung; bStats (ohne Plugin-Id inaktiv); `/orderadmin dump`; API als eigenes Modul mit Javadoc, Validierung und Beispiel-Plugin; Migration V5 für SQLite und MariaDB; Mehrserver-Tests; Lasttests; reproduzierbare Jars, SBOM, SHA-gepinnte Actions, Wrapper-Prüfsumme, Variante mit eingebetteten Treibern (ADR 0004, 0005); Verkaufs-ZIP mit Inhaltsprüfung; Dokumentation (README, CHANGELOG, UPGRADE, COMPATIBILITY, Wiki mit 13 Seiten, Issue-Vorlagen, LISTING, Drittanbieter-Hinweise, Lizenz, Marktplatzregeln).

## Nicht umgesetzt

| Punkt | Stand |
|---|---|
| Vorlagen im Menü, Erstellmenü mit allen Optionen, Massenverkauf, Auto-Verlängerung mit Gebühr, Review-Menü | nicht umgesetzt. Die Vorlagen-Tabelle und der Repository-Code existieren, es gibt keine Bedienung |
| Auto-Abholung | Flag wird gespeichert, aber nirgends ausgewertet |
| Import von Konkurrenzplugins | nicht recherchiert und nicht umgesetzt |
| Aufteilung großer Klassen (Phase 6) | nicht durchgeführt |
| PIT-Schwellen (85 % für tx, economy, item, storage, 70 % sonst) als Gradle-Prüfung | nicht eingerichtet. Ausgangswerte: tx 79 %, item 81 %, storage 73 %, economy 44 %, flow 24 %, command 26 %. Die Schwellen sind weit entfernt |
| Update-Prüfung über Plattform-API | bleibt bei GitHub-Releases, Standard aus |
| Offline-MariaDB | keine Lösung, der Treiber steht unter LGPL (ADR 0005) |
| Screenshot- und GIF-Liste mit Testserver-Aufbau, `docs/market-research.md` mit Quellen | nicht geschrieben. Recherchenotizen liegen nur im Arbeitsverzeichnis der Sitzung |

## Nicht ausgeführt

| Prüfung | Grund | Befehl zum Nachholen |
|---|---|---|
| Abschlussreview durch `plugin-security-reviewer`, `folia-concurrency-reviewer`, `paper-architect`, `plugin-test-engineer` | alle vier Agenten sind am Nutzungslimit abgebrochen und lieferten keine Befunde | Agenten nach Limit-Reset erneut starten und Befunde einzeln beheben |
| Echte Paper- und Folia-Server mit 2.0.0 | Die EULA stimmt der Assistent nicht selbst zu | `ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh` |
| Spieler-Szenario mit Bots für 2.0.0 | gleiche Voraussetzung | `node tools/e2e/e2e.js <server> <result.json>` |
| `OrderHub-2.0.0-bundled.jar` auf einem Server starten | nicht gestartet, nur gebaut und im ZIP geprüft | jar statt der normalen Datei einlegen und Server ohne Internet starten |
| `dependencyCheckAnalyze` | `NVD_API_KEY` fehlt | `NVD_API_KEY=… ./gradlew dependencyCheckAnalyze` |
| `pitest` im Endstand | nicht wiederholt | `./gradlew pitest` |
| `integrationTest` im Endstand | nicht wiederholt | `./gradlew integrationTest --max-workers=1` (Docker nötig) |
| GitHub-Workflows | kein Runner | Tag `v2.0.0` pushen |
| Geyser und Bedrock-Client | nicht verfügbar | Testserver mit Geyser und Bedrock-Client |

## Restrisiken

- Die sieben neuen Sprachen wurden nicht von Muttersprachlern geprüft.
- bStats liefert keine Daten, bis eine Plugin-Id registriert und in `MetricsSetup` eingetragen ist.
- Der Servertest von 1.1.0 (17 von 17 Prüfungen) gilt nicht automatisch für 2.0.0.
- Vault bietet keine Transaktions-Ids. Ein Absturz genau zwischen Auszahlung und Speichern des Ergebnisses endet in MANUAL_REVIEW und braucht einen Administrator.
- Die Verkaufsplattformen haben eigene Regeln (`docs/marketplace-rules.md`). Spigot Premium nimmt keine neuen Autoren, Lizenzsysteme sind dort verboten.
