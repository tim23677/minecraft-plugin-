# OrderHub Release-Bericht

Stand: 2026-11-26, Version 0.1.0-SNAPSHOT, Branch `claude/practical-cori-vbbwgd`.

## Ergebnis

OrderHub ist in diesem Stand NICHT release-fähig im Sinn der Abschlusskriterien. Block A ist nicht vollständig erledigt (Punkte 3, 5, 6 und 7 fehlen). Der Lauf auf echtem Paper und Folia (Punkt 8, Teilumfang) ist bestanden, deckt aber nur Laden, Aktivieren und einen Konsolenbefehl ab. Die Abschlussbedingung "Auftrag erstellen, liefern, abholen, Neustart, Recovery" ist auf einem echten Server nicht geprüft.

In dieser Sitzung erledigt, jeweils mit Code, Tests, Dokumentation, Changelog und grünem Build:

- Punkt 1: Vault-Aufrufe laufen standardmäßig auf dem globalen Thread (`economy.async-safe: false`, `economy.timeout-ms`). Eine Zeitüberschreitung bei withdraw oder deposit wirft `EconomyOutcomeUnknownException`. Test `GlobalThreadEconomyTest` (5 Tests), darunter "kein Provider-Aufruf außerhalb des globalen Threads".
- Punkt 2: `/orderadmin review list`, `review show <tx>`, `review resolve <tx> paid|refund|discard confirm <Grund>`. Die Aufloesung läuft über das Journal, ist idempotent (Wiederholung meldet ALREADY_RESOLVED, anderer Ausgang CONFLICT) und schreibt den Audit-Eintrag in derselben Transaktion. Test `AdminReviewTest` (6 Tests). Hinweis zur Bedeutung: `paid` schließt den Eintrag ohne Geldbewegung ab, `refund` stellt den Betrag über die Refund-Queue an den Spieler des Eintrags (nur für DELIVERY und ESCROW_CREATE, die Administration muss vorher prüfen, dass nichts gezahlt wurde), `discard` setzt den Eintrag auf FAILED.
- Punkt 4: Der Bestätigungsdialog speichert die angezeigte Gebühr, die Engine bricht vor jeder Buchung mit `FEE_CHANGED` ab, wenn sie abweicht. Die Anzeige nimmt dieselbe Gebühr wie die Abrechnung (`OrderService.quoteCreateFee`). Test `FeeConsistencyTest` (3 Tests). Grenze: Die Engine selbst liest ihre Gebühr weiterhin nur beim Start, eine geänderte Gebühr wirkt erst nach einem Neustart (die Option steht so in CONFIGURATION.md). Ein unveränderlicher Settings-Snapshot, der bei Reload ausgetauscht wird, ist nicht umgesetzt.
- Punkt 8 (Teil): mcstage-Szenario korrigiert (Wartezeit auf die asynchrone Initialisierung, richtiger erwarteter Text), CI-Job `server-smoke` in `.github/workflows/build.yml`, nur aktiv bei der Repository-Variable `ACCEPT_MINECRAFT_EULA=true`.

## Was tatsächlich gelaufen ist

| Prüfung | Befehl | Ergebnis |
|---|---|---|
| Kompilierung mit `-Xlint:all -Werror`, Spotless, SpotBugs, Tests, Shadow-Jar | `./gradlew build -x dependencyCheckAnalyze --max-workers=1` | erfolgreich, 786 Tests, 0 Fehler, 0 übersprungen |
| Echter Server Paper 1.21.11 (Build 132) und Folia 1.21.11 (Build 14) | `ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh` | 2 bestanden in 55,7 s: Plugin lädt, `OrderHub enabled (server-id main, SQLITE)`, Recovery ohne Fehler, Konsolenbefehl antwortet |

Hinweis zur Umgebung: Gradle bekam über den Proxy bei parallelen Downloads HTTP 429. Mit `--max-workers=1` läuft der Build durch. Die EULA-Zustimmung hat der Nutzer im Auftrag ausdrücklich erteilt.

Die Ergebnisse aus dem früheren Stand (Integrationstests gegen MariaDB 10.11.14: 44 Tests bestanden, Lasttest SQLite 100.000 Aufträge: p50 246 ms, p95 1.177 ms, Maximum 7.807 ms) wurden in dieser Sitzung nicht erneut ausgeführt. `./gradlew integrationTest` wurde nicht gestartet.

## Beobachtungen aus dem echten Serverlauf

- Beim Start erscheinen drei ERROR-Zeilen `SLF4J(W): No SLF4J providers were found` und eine Paper-Warnung über System.out/err-Nutzung. Ursache ist HikariCP ohne SLF4J-Provider. Funktional folgenlos, für ein Produkt aber unschön. Nicht behoben.
- Ohne Vault meldet das Plugin eine Warnung und kann keine Aufträge erstellen. Ein Lauf mit Vault und Economy-Plugin fand nicht statt.

## Offen

Block A: Punkt 3 (Rückgabe-Reservierung `item_returns` zwischen PREPARED und ITEMS_REMOVED), Punkt 5 (Discord-Outbox mit Claim), Punkt 6 (Audit in derselben Transaktion für alle Admin-Aktionen, Reload-Audit, API-Aufrufer protokollieren, Limit- und Rechteprüfung in `createOrder` der API), Punkt 7 (Treiber relokieren oder über den Library-Loader laden, Ergebnis in COMPATIBILITY.md; der CI-Job `dependency-check` mit `NVD_API_KEY` existiert, wurde hier nicht ausgeführt).

Block B: Punkt 8 (Rest: Spieler-Szenario, Neustart, Folia-Smoke-Skript), 9, 10, 11, 12, 13.

Block C: Punkte 14 bis 20.

Block D: Punkte 21 bis 25.

Bekannte Restrisiken, die aus dem früheren Bericht unverändert gelten:

- Absturz zwischen erfolgreicher Vault-Zahlung und gespeichertem Ergebnis bleibt ein Fall für MANUAL_REVIEW (keine Exactly-once-Garantie, nie automatisch wiederholt).
- Lieferung: Stürzt der Server zwischen PREPARED und ITEMS_REMOVED ab, können Items aus dem flüchtigen Menü verloren gehen (Punkt 3).
- Überzählige Items über die vorbereitete Menge hinaus gehen bei Deaktivierung während einer Lieferung nicht zurück.
- Audit-Einträge der älteren Admin-Befehle entstehen in einer eigenen Transaktion nach der Aktion, `reload` wird nicht auditiert (Punkt 6). Der neue Befehl `review resolve` schreibt dagegen in derselben Transaktion.
- API-Aufrufe von Drittplugins werden nicht dem aufrufenden Plugin zugeordnet, `createOrder` der API prüft keine Spielerberechtigung.
- JDBC-Treiber sind nicht relokiert.
- Mehrere Server können dieselbe Discord-Outbox-Zeile doppelt senden.
- MySQL 8 ist nicht unterstützt (`CREATE INDEX IF NOT EXISTS`), das steht im README.
- Suchleistung: p95 über 1 s bei 100.000 Aufträgen ohne Item-Filter.

## Reviews mit Agenten und Behebung

Drei Review-Agenten (Folia-Threading, Sicherheit, Architektur) und der automatische Sicherheitsreview der Umgebung haben den Code geprüft. Behoben wurden unter anderem:

- Item-Duplikation beim Schließen oder Trennen nach "Bestätigen" in der Lieferung (eingefrorene Menüs geben beim Schließen nichts heraus, der Ablauf nimmt die Items selbst).
- Abschluss einer Lieferung unabhängig vom Spieler (Ereignisse, Statistik, Auszahlung), Rückgabe des Überschusses auch bei abwesendem Spieler.
- `tryRunOn` auf Paper meldet nun `false` für getrennte Spieler.
- Recovery gab Reservierungen mit begonnener Übergabe frei (Dupe) und prüfte Veraltung nur auf der Liste, nicht in der Einzeltransaktion.
- Auftragslimit ließ sich über mehrere Server und unter REPEATABLE READ mit altem Snapshot umgehen.
- Recovery mit Null-Schwelle im laufenden Betrieb (jetzt nur beim Start eines einzelnen SQLite-Servers).
- Auszahlungen eskalierten bei kurzem Provider-Ausfall nach MANUAL_REVIEW (UNAVAILABLE eskaliert nun nie).
- Lebensdauer von API-Aufträgen wird vor der Abbuchung begrenzt.
- Admin-Rechte pro Unterbefehl (`orderhub.admin.view`, `.cancel`, `.reload`, `.transactions`, `.recovery`).
- Datenbank-SSL-Modus konfigurierbar mit Warnung bei nicht lokalem Host ohne Verschlüsselung.
- Menü-Disable auf Folia fail closed, PlaceholderAPI-Abmeldung, Logging verlorener Rückgaben, Fehlerrückmeldung bei Datenbankproblemen.

## Finale Sicherheitsprüfung nach Kategorien

Alle Angaben beruhen auf Code-Review durch Agenten und den genannten Tests, nicht auf einem Lauf auf einem echten Server.

| Kategorie | Geprüft in | Test oder Prüfung | Ergebnis und Einschränkung |
|---|---|---|---|
| 1 Item-Dupes | gui, flow, tx | GuiSecurityTest, MenuFreezeTest, MenuLifecycleTest, ParcelEngineTest | bestanden. DeliveryFlow selbst ungetestet, Restfenster siehe oben |
| 2 Money-Dupes | tx, service | PayoutWorkerTest, OrderServiceTest (Idempotenz, parallel) | bestanden |
| 3 Escrow-Konsistenz | service, tx | OrderServiceTest, Invariants-Prüfung in Engine-Tests | bestanden |
| 4 Transaction Journal | tx | TxStateMachineTest (407), Storage-Contract-Tests | bestanden |
| 5 Idempotenz | tx, storage | OrderServiceTest, Journal-Contract-Tests | bestanden |
| 6 Distributed Locking | storage | Contract-Tests auf MariaDB (Row-Lock, Limit, CAS) | bestanden auf einer MariaDB-Instanz |
| 7 Race Conditions | tx, storage | Parallelitätstests (20 parallele Erstellungen, 8 Threads Payout) | bestanden |
| 8 Crash Recovery | tx | RecoveryServiceTest, ParcelEngineTest, PayoutWorkerTest (Absturz nach jedem Zustand) | bestanden. Restfenster siehe oben |
| 9 Claim-Sicherheit | tx, flow | ParcelEngineTest | Engine bestanden, ParcelFlow ungetestet |
| 10 Return Queue | flow, tx | ParcelEngineTest, MenuLifecycleTest | teilweise, persistenter Schreibweg nur über Engine-Tests |
| 11 Inventory Security | gui | GuiSecurityTest (53) | bestanden unter MockBukkit |
| 12 Vault-Auszahlungsfehler | economy, tx | PayoutWorkerTest | bestanden gegen eine simulierte Economy, kein echter Provider |
| 13 Datenbankausfälle | storage, tx | Retry-Tests, Fehlerinjektion in Engine-Tests | bestanden simuliert |
| 14 SQL-Injection | storage | Contract-Tests, Review aller Jdbc-Klassen | bestanden |
| 15 Folia-Thread-Safety | scheduler, flow, gui | Review durch Agent, behoben | nicht auf Folia ausgeführt |
| 16 Paper-Kompatibilität | gesamt | MockBukkit 1.21 | kein echter Paper-Lauf |
| 17 MySQL-Netzwerksicherheit | bootstrap | ssl-mode mit Warnung | konfiguriert, kein Test mit TLS-Server |
| 18 Cache-Invalidierung | placeholder | PlaceholderCacheTest | Outbox-Cache nicht umgesetzt |
| 19 Admin-Berechtigungen | command | OrderCommandTest | teilweise, siehe Restrisiken |
| 20 API-Sicherheit | api, integration | Review | teilweise, kein eigener Test |

## Abschlusskriterien

| Kriterium | Stand |
|---|---|
| Projekt kompiliert vollständig | erfüllt |
| Relevante Tests tatsächlich ausgeführt | erfüllt (siehe oben), mit den genannten Lücken |
| Alle kritischen Tests bestanden | erfüllt für die vorhandenen Tests |
| Keine bekannten kritischen Sicherheitslücken | im Review gefundene kritische und hohe Punkte sind behoben, Restfenster stehen oben |
| Paper-Kompatibilität praktisch geprüft | NICHT erfüllt |
| Folia-Kompatibilität praktisch geprüft | NICHT erfüllt |
| SQLite funktioniert | erfüllt (Tests) |
| MariaDB funktioniert | erfüllt (44 Integrationstests), MySQL nicht geprüft |
| Netzwerkweite Auftragsverwaltung | auf Datenbankebene getestet, nicht mit zwei Servern, Cache-Synchronisation fehlt |
| Transaktionen und Recovery getestet | erfüllt |
| GUI-Interaktionen getestet | erfüllt unter MockBukkit |
| Dokumentation vollständig | vorhanden (SPEC, ARCHITECTURE, PLAN, DATABASE, SECURITY, API, CONFIGURATION, COMPATIBILITY, TESTPLAN, README, CHANGELOG, LISTING, LICENSE), das Listing nennt die Lücken ehrlich |
| Release-Jar erstellt und geprüft | erstellt, Inhalt (plugin.yml, Klassen) geprüft, nicht auf einem Server geladen |
| Lizenz enthalten | erfüllt |
| BuiltByBit-Listing fertig | als Entwurf vorhanden, vor Veröffentlichung gegen die tatsächliche Version prüfen |

## Empfohlene nächste Schritte

1. Auf einem Test-Paper- und einem Test-Folia-Server laufen lassen (`ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh`, EULA-Zustimmung durch die verantwortliche Person) und `TESTPLAN.md` durchgehen.
2. `NVD_API_KEY` setzen und `./gradlew dependencyCheckAnalyze` ausführen.
3. Admin-Befehl zum Auflösen von MANUAL_REVIEW und atomares Audit ergänzen.
4. Tests für `DeliveryFlow`, `ParcelFlow` und `CreateFlow` mit MockBukkit schreiben.
5. Cache-Outbox umsetzen oder aus Spezifikation und Konfiguration entfernen, tote Konfigurationsoptionen anbinden oder entfernen.
6. Fehlende GUI-Menüs und Funktionen (Vorlagen, Bulk, Auto-Verlängerung, Reputation) umsetzen oder aus dem Umfang nehmen.
