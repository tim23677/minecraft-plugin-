# Simulationsbericht OrderHub 2.0.0

Stand: 2026-11-28. Nichts davon ist committet.

## Umgebung

- Paper 1.21.11, Build 132, Jar per Fill-API geladen, SHA256 5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba geprüft.
- Java: OpenJDK 21.0.13.1 (war vorhanden, kein Download nötig).
- Abhängigkeiten, alle von Modrinth mit geprüfter SHA512: VaultUnlocked 2.20.3 (liefert Vault 2.20.3), EssentialsX 2.22.0 als Wirtschaft, LuckPerms 5.5.71 für das Entziehen und Setzen von Rechten.
- Datenbanken: SQLite, und MariaDB 11.4 im Docker-Container.
- Spieler: mineflayer 4 mit den Bots SimAdmin (OP), SimPlayer, SimThird und SimQuitter.
- server.properties wie vorgegeben: online-mode=false, feste Port-Nummer 25598, flat, spawn-protection=0, view-distance=4, Server mit höchstens 2 GB Heap. Die EULA ist auf Deinen Auftrag in `sim/server/eula.txt` akzeptiert.
- Nicht installiert, weil im Plugin nicht benötigt: ProtocolLib, Redis. PlaceholderAPI und WireMock sind nicht eingerichtet, siehe offene Punkte.

Alles liegt in `sim/` und ist mit `sim/run.sh` wiederholbar (`SIM_DB=mariadb sim/run.sh` für MariaDB). Ergebnisse stehen in `sim/out/<sqlite|mariadb>/` als `result.json` und `server.log`. Der Testplan ist `TESTPLAN.md`.

## Ergebnis

| Lauf | Ergebnis |
|---|---|
| SQLite, Befehle, Rechte, Ereignisse, Neustart, Konfiguration | 153 von 153 bestanden |
| SQLite, harter Absturz (SIGKILL) während einer Lieferung, Recovery (`tools/e2e`) | 17 von 17 bestanden |
| MariaDB, gleiche 153 Prüfungen | 153 von 153 bestanden |
| `./gradlew spotlessApply build -x dependencyCheckAnalyze` | erfolgreich, 1161 Tests, 0 Fehler |

Im Server-Log beider Läufe gibt es keine Exception und keine SEVERE-Zeile. Die Warnung "Can't keep up" kam nicht vor.

## Getestet

Befehle, jeweils mit gültigen, ungültigen und fehlenden Argumenten:
- `/order` mit allen Unterbefehlen: Hauptmenü, help, create (Chat-Eingabe, falsche Menge, Preis, Option, Bestätigen, Abbrechen), mine, market (Sortierung, Suche), sell, deliver, cancel, info, renew, template (save, list, use, delete, Menü, falscher Name, falsche Option), claims, returns, stats, history, favorites, analytics.
- `/orderadmin` mit allen Unterbefehlen mit Recht (statistics, list, health, debug, transactions, inspect, review, dump, cancel, backup, export, recovery, purge-expired, reload) und ohne Recht.
- Rechte über LuckPerms: `orderhub.use`, `.create`, `.market`, `.stats`, `.favorites` einzeln entzogen, ein einzelnes Adminrecht gesetzt.

Ereignisse und Abläufe:
- Auftrag mit Optionen anlegen, Optionen in der Datenbank, `/order info`, Storno durch einen Administrator mit Erstattung (Kontostand stimmt auf den Cent), Audit-Einträge.
- Lieferung mit 10 gleichzeitigen Klicks auf Bestätigen: genau eine Lieferung, genau ein Journal-Eintrag, kein Item verloren oder verdoppelt.
- Shift-Klick, Zahlentaste und Icon-Klick im Menü: keine Lieferung, Items kommen zurück.
- Tod und Teleport bei offenem Menü, Quit bei offener Chat-Eingabe und bei offenem Menü, normaler Chat bleibt unberührt, Blockänderungen.
- 30 Befehle in 9 Sekunden: Server antwortet, keine Thread-Fehler.
- Neustart: Aufträge und Transaktionen bleiben erhalten, Recovery läuft.
- Konfiguration: ungültige Werte erzeugen Warnungen und fallen auf Standardwerte, Menge 1 und Gebühr 0, unbekannte Sprache fällt auf Englisch, Sprache de.

## Gefundene Fehler

1. Eingabe der Menge im Chat. Bei `/order create` ohne Argumente prüfte das Plugin die Menge erst, nachdem auch der Preis eingegeben war. Eine Eingabe wie "abc" führte also zur Preisabfrage und erst danach zur Fehlermeldung. Ursache: `CreateFlow.start` rief `withQuantity` erst im zweiten Eingabeschritt auf. Behoben: die Menge wird sofort geprüft, bei Fehler kommt die Meldung und der Ablauf endet. Regressionstests: `CreateFlowTest.anInvalidQuantityTypedInChatIsRejectedBeforeThePricePrompt` und `aValidQuantityTypedInChatLeadsToThePricePrompt`.

Weitere Abweichungen der ersten Läufe lagen im Testskript und nicht im Plugin: Der Start wurde gemeldet, bevor das Plugin fertig aktiviert war (das Plugin meldet "enabled" erst nach dem asynchronen Datenbankstart), `/order stats` und `/order history` zeigen ohne Daten einen Hinweis statt eines Menüs, die Ablehnung eines fremden Auftrags heißt "This is not your order.", und Paper zeigt Spielern ohne Recht für `/orderadmin` "Unknown or incomplete command", weil der Befehl für sie nicht sichtbar ist. Das ist das sichere Verhalten.

## Beobachtungen ohne Änderung

- `/reload confirm` kennt Paper 1.21.11 nicht ("Incorrect argument for command"). Ein Plugin-Reload ließ sich deshalb nicht simulieren. Der Datapack-Reload (`minecraft:reload`) stört OrderHub nicht.
- Vanilla kickt Spieler, die mehr als etwa einen Befehl pro Sekunde senden ("Kicked for spamming"). Für die Simulation sind `/order` und `/orderadmin` in `sim/server/spigot.yml` von dieser Sperre ausgenommen. Auf einem echten Server gilt die Sperre weiter, das ist gewollt.
- `/orderadmin backup` meldet bei MariaDB "backup is only available for SQLite, use mysqldump", wie dokumentiert.

## Auszüge aus dem Log

- `[OrderHub] OrderHub enabled (server-id main, SQLITE)` und `Recovery: ... errors=0 existingManualReview=0` bei jedem Start.
- Nach dem harten Abbruch: `Recovery: ... itemsReturned=1`, Journal FAILED, 9 Items als Rückgabe, keine offene Reservierung, kein MANUAL_REVIEW.
- Konfigurationswarnungen wie `config.yml: orders.max-quantity=-5 out of range 1..1000000, using 2304`.

## Änderungen im Repository (nicht committet)

- `src/main/java/dev/orderhub/flow/CreateFlow.java`: Sofortprüfung der Menge.
- `src/test/java/dev/orderhub/flow/CreateFlowTest.java`: zwei neue Tests.
- `tools/e2e/e2e.js`: Port und Version über Umgebungsvariablen einstellbar.
- `TESTPLAN.md`: neu geschrieben.
- `sim/` (neu): `run.sh`, `setup.py`, `bots/test.js`, `.gitignore`. Server, JDK, node_modules und Ergebnisse sind ausgeschlossen.
- `SIMULATION_REPORT.md` (diese Datei).

## Offen

- Folia: in dieser Simulation nicht gelaufen. Der frühere Lauf über `tools/mcstage` fand vor den letzten Änderungen statt.
- PlaceholderAPI: nicht installiert, die Platzhalter sind nur durch Unit-Tests abgedeckt.
- Discord-Webhook: nicht simuliert. Die Adressprüfung des Plugins lässt lokale Mock-Server nicht zu, WireMock war deshalb nicht einsetzbar.
- Mehrere Server gleichzeitig an einer MariaDB: nicht simuliert, nur ein Server lief gegen MariaDB. Dafür gibt es die Integrationstests mit simulierten Servern.
- Speicherverlauf und TPS unter Last: nicht gemessen, nur auf "Can't keep up" und Thread-Fehler im Log geprüft.
- Geyser und Bedrock-Clients: nicht getestet.
- Plugin-Reload im laufenden Betrieb: von Paper in dieser Version nicht unterstützt.
- Die übrigen Punkte des Master-Prompts (zum Beispiel PIT-Schwellen für `flow`, `command`, `service`, Dependency-Check mit NVD-Schlüssel, Refactoring der großen Klassen) sind unverändert offen, siehe `RELEASE_REPORT.md`.
