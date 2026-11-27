# OrderHub Release-Bericht

Stand: 2026-11-26, Version 1.1.0, Branch `claude/practical-cori-vbbwgd`.

## Ergebnis

Alle in dieser Umgebung ausführbaren Prüfungen sind grün (Tabelle unten). Neu gegenüber 1.0.0: ein vollständiges Spieler-Szenario auf einem echten Paper-Server mit Vault und EssentialsX (17 von 17 Prüfungen, einschließlich hartem Absturz mitten in einer Lieferung), Flow-Tests für Lieferung, Erstellung und Abholung, Betriebswerkzeuge (health, backup, export), eine Konfigurationsmigration, eine abschaltbare Update-Prüfung und eine Release-Pipeline. Offen bleiben die Punkte im Abschnitt "Nicht umgesetzt".

## Was tatsächlich gelaufen ist

| Prüfung | Befehl | Ergebnis |
|---|---|---|
| Build mit `-Xlint:all -Werror`, Spotless, SpotBugs (Main und Test), Tests, Jar | `./gradlew clean build -x dependencyCheckAnalyze --max-workers=1` | erfolgreich, 860 Tests, 0 Fehler, 0 übersprungen |
| Integrationstests gegen MariaDB 11.4 (Testcontainers, Docker-Daemon in der Sitzung gestartet, Image über mirror.gcr.io) | `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew integrationTest --max-workers=1` | 49 bestanden, 1 übersprungen (SQLite-Test) |
| Lasttest SQLite, 100.000 Aufträge, 480 Suchen auf 8 Threads | `./gradlew loadTest --max-workers=1` | p50 19 ms, p95 156 ms, Maximum 867 ms |
| Echter Server Paper 1.21.11 und Folia 1.21.11 mit Konsolenbefehlen für health, backup, export und review | `ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh` | 2 bestanden in 82,8 s |
| Spieler-Szenario: Paper 1.21.11, VaultUnlocked 2.20.3, EssentialsX 2.22.0, zwei mineflayer-Bots | `node tools/e2e/e2e.js ../srv ../result.json` | 17 von 17 bestanden |

Die EULA-Zustimmung hat der Nutzer im Auftrag ausdrücklich erteilt. Gradle bekam über den Proxy bei parallelen Downloads HTTP 429, daher `--max-workers=1`. Docker, Server und Tests liefen nacheinander, weil die Sandbox bei gleichzeitigem Betrieb Threads erschöpft.

## Umgesetzt in dieser Runde

- Spieler-Szenario auf echtem Server (`tools/e2e/e2e.js`, Anleitung in `tools/e2e/README.md`). Ein harter Kill (SIGKILL) kam im Fenster nach dem Bestätigen und vor dem Commit an: Die Recovery hat die 9 Items im Menü über die Rückgabe-Reservierung zurückgegeben, ohne Verlust und ohne Duplikat.
- MockBukkit-Flow-Tests: `DeliveryFlowTest` (10), `CreateFlowTest` (9), `ParcelFlowTest` (4). Sie decken ab: Normalfall mit Überschuss, Abbrechen, Schließen, Disconnect, dreifacher Klick auf Bestätigen, volles und teilweise volles Inventar, Datenbankausfall (fail closed), verlorene Commit-Bestätigung, unklare Zahlung (MANUAL_REVIEW, nie wiederholt), Storno während der Lieferung, Rate Limit, ungültige Eingabe. Dafür gibt es in `ItemService` jetzt `nameOf`, das auf den Materialnamen zurückfällt, wenn `effectiveName()` fehlt (MockBukkit kennt es nicht).
- `/orderadmin health`, `/orderadmin backup`, `/orderadmin export orders|ledger`, neue Permission `orderhub.admin.backup`. Automatische Sicherung vor jeder Schema-Migration einer bestehenden SQLite-Datenbank (Migration wird abgelehnt, wenn die Sicherung scheitert). CSV mit Schutz vor Formeln. Backup und Export sind auf dem echten Server durchgelaufen (Dateien liegen in `backups` und `exports`).
- Konfiguration: `config-version` 2 mit Migrationsschritten, `ConfigUpdater` bereinigt entfernte Optionen, behält Werte und Kommentare, legt `.bak` an und lässt eine Datei einer neueren Version unverändert (mit Warnung). Bei einer tatsächlich vorhandenen alten Konfiguration auf dem Testserver wurde die Migration ausgeführt.
- Optionale Update-Prüfung gegen GitHub-Releases (`updates.check`, Standard aus), geprüft mit einem lokalen HTTP-Server (6 Tests).
- Release-Pipeline: `.github/workflows/release.yml` (Tag `v*` baut, packt, attestiert die Provenance und veröffentlicht das Release), `tools/release/package.sh` baut beide ZIPs. Die Pipeline selbst ist hier nicht gelaufen (kein GitHub-Runner).

## Nicht umgesetzt oder nicht ausführbar

- Dependency-Check: `dependencyCheckAnalyze` braucht `NVD_API_KEY`, in der Umgebung gab es keinen. Die Bibliotheken sind nicht auf bekannte Schwachstellen geprüft.
- Menüs für Statistik, Verlauf, Favoriten, Marktanalyse und Admin-Dashboard: nicht umgesetzt, die Ausgabe bleibt im Chat.
- Keyset-Pagination und MariaDB-Lasttest: nicht umgesetzt, die Suche ist über Indizes (Migration V4) schnell genug für SQLite, auf MariaDB nicht gemessen.
- `OrderHubPlugin` und `OrderService` zerlegen, API in ein eigenes Modul auslagern: nicht umgesetzt.
- Strukturierte Logzeilen mit Transaktions-ID je Geldbewegung und bStats: nicht umgesetzt.
- Auto-Verlängerung, Mindestliefermenge und Vorlagen im Erstellmenü, aktiver ChatInput-Timeout, Aufräumen der Cooldown-Tabelle beim Verlassen: nicht umgesetzt.
- GitHub Actions in `build.yml` und `release.yml` sind über Versions-Tags statt feste Commit-SHAs eingebunden (Befund eines Sicherheits-Reviews). Renovate ist so konfiguriert, dass es die SHAs vorschlägt. Das Pinnen habe ich hier nicht selbst gemacht, weil ich die SHAs nicht prüfen konnte. Vor dem ersten Release den Renovate-PR zusammenführen.
- Paper-API ist weiter ein Snapshot (`1.21.11-R0.1-SNAPSHOT`), Builds sind nicht bitgleich reproduzierbar.
- Spieler-Szenario auf Folia (EssentialsX läuft dort nicht), mit anderen Economy-Plugins, mit MariaDB und zwei Servern: nicht ausgeführt.

## Bekannte Restrisiken

- Absturz zwischen erfolgreicher Vault-Zahlung und gespeichertem Ergebnis bleibt ein Fall für MANUAL_REVIEW (keine Exactly-once-Garantie, nie automatisch wiederholt). Auflösbar mit `/orderadmin review resolve`.
- Items im Liefermenü sind erst ab dem Bestätigen durch die Reservierung gesichert. Ein Absturz, während Items im geöffneten Menü liegen und noch nicht bestätigt sind, kann sie verlieren (das Menü existiert nur im Speicher). Das ist Verhalten jedes Inventar-GUIs und im Szenario nicht abgedeckt.
- Fällt die Datenbank länger als etwa eine Minute aus, nachdem `prepare` einen unklaren Ausgang hatte, bleiben die Items im Menü und werden nur im Log gemeldet (SEVERE). Es entsteht kein Dupe, aber ein Verlust, der manuell auszugleichen ist.
- Nach einem harten Absturz stellt der Server den Spieler-Zustand auf den zuletzt gespeicherten Stand zurück (Vanilla-Verhalten). Das betrifft Items im Spielerinventar, nicht die Items, die OrderHub verbucht hat.
- Ein langsamer Economy-Provider blockiert den globalen Thread bis `economy.timeout-ms`.
- Der erste Start braucht Zugriff auf Maven Central für die Bibliotheken.
- Die Cooldown-Tabelle im Speicher wächst mit der Zahl der Spieler, die je erstellt haben, bis zum Neustart.
