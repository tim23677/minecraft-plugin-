# Ausgangslage OrderHub 1.1.0

Gemessen am 27.11.2026 auf dem Quellstand aus dem Archiv `OrderHub-1.1.0-source.zip` (Commit `8520b52`), bevor Änderungen für 2.0.0 gemacht wurden. Alle Zahlen stammen aus den genannten Läufen.

## Build und Tests

Befehl: `./gradlew build -x dependencyCheckAnalyze --max-workers=1`

Ergebnis: erfolgreich. 860 Tests, 0 Fehler, 0 Fehlschläge, 0 übersprungen. Reine Testlaufzeit laut JUnit-Berichten 13,2 s. Der Gradle-Lauf dauerte inklusive Compile, Spotless und SpotBugs 54 s bei warmem Cache (der erste Versuch ohne Cache scheiterte an HTTP 429 von Maven Central beim SpotBugs-Download, der serielle Wiederholungslauf bestand).

Spotless, SpotBugs (Main und Test) und `-Xlint:all -Werror` liefen und waren grün.

## Integrationstests

Auf dem unveränderten 1.1.0-Stand habe ich `integrationTest` nicht einzeln gemessen. Der erste Lauf war nach Phase 1 (Docker-Daemon in der Sitzung gestartet, Image `mariadb:11.4` über `mirror.gcr.io`, `TESTCONTAINERS_RYUK_DISABLED=true`): 50 Tests, 0 Fehler, 1 übersprungen. Der RELEASE_REPORT von 1.1.0 nennt 49 bestanden und 1 übersprungen.

## Mutationsabdeckung (PIT)

Befehl: `./gradlew pitest` in einer Kopie des Quellstands mit zusätzlichem XML-Ausgabeformat (nur in der Kopie, nicht im Repository). Laufzeit 19 min 1 s. PIT erzeugte 3051 Mutationen, 1695 wurden von Tests getötet (55.6 %), 455 überlebten, 873 lagen ohne Testabdeckung. Laut PIT-Ausgabe: Zeilenabdeckung der mutierten Klassen 71 %, Testschärfe 79 %.

Die Spalte "Sonstige" fasst Zeitüberschreitungen, nicht lauffähige Mutanten und Laufzeitfehler zusammen. Während des Laufs gab es mehrere PIT-Warnungen "Minion exited abnormally due to TIMED_OUT", weil parallel andere Gradle-Läufe auf derselben Maschine liefen. Die Zahlen sind daher eine Untergrenze der getöteten Mutationen.

| Paket | Mutationen | getötet | überlebt | ohne Abdeckung | sonstige | getötet in % |
|---|---|---|---|---|---|---|
| dev.orderhub | 123 | 32 | 40 | 44 | 7 | 26.0 % |
| dev.orderhub.api | 4 | 0 | 0 | 4 | 0 | 0.0 % |
| dev.orderhub.api.event | 21 | 0 | 0 | 21 | 0 | 0.0 % |
| dev.orderhub.bootstrap | 122 | 97 | 17 | 8 | 0 | 79.5 % |
| dev.orderhub.command | 267 | 70 | 25 | 172 | 0 | 26.2 % |
| dev.orderhub.config | 14 | 13 | 1 | 0 | 0 | 92.9 % |
| dev.orderhub.discord | 90 | 72 | 13 | 5 | 0 | 80.0 % |
| dev.orderhub.domain | 33 | 29 | 3 | 1 | 0 | 87.9 % |
| dev.orderhub.economy | 27 | 12 | 2 | 10 | 3 | 44.4 % |
| dev.orderhub.economy.vault | 31 | 0 | 0 | 31 | 0 | 0.0 % |
| dev.orderhub.flow | 465 | 113 | 121 | 230 | 1 | 24.3 % |
| dev.orderhub.gui | 265 | 172 | 37 | 56 | 0 | 64.9 % |
| dev.orderhub.integration | 118 | 31 | 14 | 72 | 1 | 26.3 % |
| dev.orderhub.item | 165 | 134 | 9 | 22 | 0 | 81.2 % |
| dev.orderhub.market | 30 | 28 | 2 | 0 | 0 | 93.3 % |
| dev.orderhub.money | 39 | 24 | 9 | 6 | 0 | 61.5 % |
| dev.orderhub.placeholder | 40 | 9 | 4 | 27 | 0 | 22.5 % |
| dev.orderhub.scheduler | 53 | 19 | 10 | 23 | 1 | 35.8 % |
| dev.orderhub.service | 306 | 205 | 38 | 63 | 0 | 67.0 % |
| dev.orderhub.storage | 467 | 341 | 74 | 44 | 8 | 73.0 % |
| dev.orderhub.tx | 371 | 294 | 36 | 34 | 7 | 79.2 % |

Die Schwellen aus Phase 6 (tx, economy, item, storage mindestens 85 %, übrige mindestens 70 %) sind auf dem Ausgangsstand nur teilweise erreicht. Die 70 % erreichen bootstrap (79,5 %), config (92,9 %), discord (80,0 %), domain (87,9 %) und market (93,3 %). Von den vier Kernpaketen liegt keines bei 85 %: tx 79,2 %, item 81,2 %, storage 73,0 %, economy 44,4 %. Unter 70 % liegen unter anderem flow (24,3 %), command (26,2 %), gui (64,9 %), service (67,0 %) und integration (26,3 %).

## Behobene Widersprüche in der Dokumentation

- CLAUDE.md, SPEC.md, ARCHITECTURE.md und DATABASE.md nannten MySQL als unterstützt. Das Plugin lehnt MySQL beim Start ab (`Migrations.detect`, Test `MigrationsTest.mysqlIsRejectedWithAClearMessageAndMariaDbAndSqliteAreAccepted`). Die Texte sagen jetzt, dass nur SQLite und MariaDB unterstützt sind.
- `config.yml` sagte, Sprachdateien lägen in `plugins/OrderHub/lang`. Tatsächlich liegen `messages_<sprache>.yml` direkt in `plugins/OrderHub`. Der Kommentar ist korrigiert.
- In `messages_de.yml` stand `market.no-data` noch auf Englisch.
