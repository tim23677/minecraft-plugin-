# OrderHub Release-Bericht

Stand: 2026-11-26, Version 0.1.0-SNAPSHOT, Branch `claude/friendly-lamport-fgt8mq`.

## Ergebnis

OrderHub ist in diesem Stand NICHT "RELEASE READY" im Sinn der Abschlusskriterien. Der Kern (Aufträge, Escrow, Lieferung, Abholung, Rückgabe, Journal, Recovery, SQLite und MariaDB, Befehle, Menüs, Discord, API, PlaceholderAPI) ist gebaut, kompiliert warnungsfrei und ist automatisiert getestet. Mehrere Pflichtpunkte sind nicht erfüllt oder nicht geprüft. Sie stehen unten einzeln.

Das Jar liegt unter `release/OrderHub-0.1.0-SNAPSHOT.jar` (SHA-256 in `release/SHA256SUMS`, erzeugt mit `./gradlew release`). Es ist ein Entwicklungsstand für einen Testserver, kein verkaufsfertiges Produkt.

## Was tatsächlich gelaufen ist

| Prüfung | Befehl | Ergebnis |
|---|---|---|
| Kompilierung mit `-Xlint:all -Werror`, Spotless, SpotBugs (Main und Test), Shadow-Jar | `./gradlew build -x dependencyCheckAnalyze` | erfolgreich |
| Unit- und MockBukkit-Tests auf SQLite | im Build enthalten | 722 Tests, 0 Fehler, 0 übersprungen |
| Integrationstests gegen echte MariaDB 10.11.14 (lokal gestartet) | `./gradlew integrationTest` mit `ORDERHUB_TEST_MARIADB_*` | 44 Tests, 0 Fehler, 0 übersprungen |
| Lasttest SQLite, 100.000 Aufträge, 8 parallele Sucher | `./gradlew loadTest` | Einfügen 10,2 s (9.784 Aufträge/s). 480 Suchen in 27,7 s Wandzeit. Latenz je Suche: p50 246 ms, p95 1.177 ms, Maximum 7.807 ms |

Zum Lasttest: SQLite nutzt hier einen Verbindungs-Pool der Größe 1, deshalb stehen parallele Suchen an. Langsame Fälle sind Suchen ohne Item-Filter mit bestimmten Sortierungen (ohne passenden Index). Ein MariaDB-Lasttest wurde nicht ausgeführt.

Der Snapshot-Test für das Auftragslimit (`ownerLimitHoldsWhenTheTransactionAlreadyReadBeforeLocking`) wurde gegen MariaDB einmal ohne und einmal mit dem Fix ausgeführt: ohne Fix fehlgeschlagen, mit Fix bestanden.

## Was NICHT geprüft wurde

- Kein Lauf auf einem echten Paper- oder Folia-Server. `tools/mcstage/run.sh` braucht die EULA-Zustimmung der verantwortlichen Person (siehe CLAUDE.md), sie wurde nicht erteilt und nicht stellvertretend gegeben. Das Jar wurde nur unter MockBukkit geladen. Die Kriterien "Paper-Kompatibilität praktisch geprüft" und "Folia-Kompatibilität praktisch geprüft" sind damit offen. Die Folia-Pfade wurden von einem Review-Agenten gelesen, nicht ausgeführt.
- `dependencyCheckAnalyze` nicht ausgeführt (kein `NVD_API_KEY`). Die Bibliotheken sind nicht auf bekannte CVEs geprüft.
- PIT (Mutationstest) nicht ausgeführt.
- MySQL nicht getestet (nur MariaDB 10.11). Die Migrationen nutzen `CREATE INDEX IF NOT EXISTS`, das MySQL 8 nicht kennt.
- Der Testcontainers-Pfad ist nicht gelaufen (kein Docker-Daemon). Die MariaDB-Tests liefen gegen eine lokal installierte Instanz.
- Die Bukkit-nahen Abläufe `DeliveryFlow`, `ParcelFlow`, `CreateFlow`, `Menus`, `InsightsFlow` haben keine eigenen automatisierten Tests. Getestet sind die Engines darunter, das GUI-Framework, die Befehle (Berechtigungen, Tab-Vervollständigung, Fehlerfälle) und die Dienste.
- Kein manueller Testplan durchgeführt. `TESTPLAN.md` enthält 23 Szenarien mit "Tatsächliches Ergebnis: nicht ausgeführt".
- Vault-Provider: Aufrufe laufen standardmäßig auf dem globalen Thread (Punkt 1 erledigt, `GlobalThreadEconomyTest`, 5 Tests). Mit einem echten Economy-Plugin nicht geprüft.
- Mehrere-Server-Betrieb ist nur auf Datenbankebene getestet (zwei Storage-Instanzen gegen eine MariaDB), nicht mit zwei echten Servern.

## Nicht umgesetzt oder nur teilweise (Vergleich mit dem Auftrag)

Nicht vorhanden:

- Cache mit Outbox-Polling und serverübergreifender Invalidierung. Es gibt nur den Unterbau (Tabelle `cache_outbox`, Repository, Test). Es gibt keinen Cache außer dem kurzlebigen Platzhalter-Cache.
- Auftragsvorlagen, Bulk-Erstellung, Reputation, Gruppen- und Weltbeschränkungen, Sound-Konfiguration (Option vorhanden, ohne Wirkung).
- Bedienbare Auto-Verlängerung und Mindestliefermenge (die Engine kann beides, die Befehle setzen `false` bzw. 1).
- GUI-Menüs für Statistik, Marktanalyse, Favoriten, Verlauf, Admin-Dashboard und Transaction Inspector. Diese Funktionen gibt es als Chatausgabe und Befehl.
- Admin-Befehl zum Auflösen von MANUAL_REVIEW-Einträgen. Ein Administrator muss unklare Zahlungen außerhalb von OrderHub klären.
- Konfigurationsversionierung mit Migrationsregeln: `config-version` und `menu-version` werden nicht ausgewertet, `ConfigUpdater` ergänzt nur fehlende Schlüssel.
- Die Optionen `cache.*`, `create-cooldown-seconds`, `recommendation-window-days`, `notifications.batch-window-seconds`, `reputation.enabled`, `sounds.*`, `commands.aliases` und die meisten Einträge in `menus.yml` sind eingelesen, haben aber keine Wirkung (in CONFIGURATION.md vermerkt).
- Eigene Pakete `order`, `transaction`, `statistics`, `notification`, `migration`, `security`, `util` laut Vorgabe: Die Funktionen liegen in `service`, `tx`, `storage`, `flow`, `bootstrap`.
- `TransactionStateChangeEvent` wird nur für Auftragserstellung und Lieferung ausgelöst, nicht für jeden Zustand der Auszahlungswarteschlange.

Bekannte Restrisiken:

- Absturz zwischen erfolgreicher Vault-Zahlung und gespeichertem Ergebnis: bleibt ein Fall für MANUAL_REVIEW (keine Exactly-once-Garantie, nie automatisch wiederholt).
- Lieferung: Stürzt der Server in den Millisekunden zwischen Journalzustand PREPARED und ITEMS_REMOVED ab, oder wird das Plugin dort deaktiviert, können Items aus dem flüchtigen Menü verloren gehen (die Recovery setzt den Eintrag dann auf FAILED ohne Rückgabe).
- Wird das Plugin während einer laufenden Lieferung deaktiviert, entscheidet die Recovery anhand des Journals. Überzählige Items über die vorbereitete Menge hinaus gehen in diesem Fall nicht zurück.
- Audit-Einträge für Admin-Befehle werden in einer eigenen Transaktion nach der Aktion geschrieben. `reload` wird nicht auditiert.
- API-Aufrufe von Drittplugins werden nicht dem aufrufenden Plugin zugeordnet protokolliert. `createOrder` nutzt das Standardlimit und prüft keine Spielerberechtigung.
- Die angezeigte Erstellgebühr kommt aus der aktuellen Konfiguration, abgerechnet wird mit dem beim Start geladenen Wert. Nach `/orderadmin reload` mit geänderter Gebühr weichen beide ab.
- Die JDBC-Treiber (SQLite, MariaDB) sind nicht relokiert. Konflikte mit Plugins, die andere Versionen bündeln, sind nicht getestet.
- Mehrere Server können dieselbe Discord-Outbox-Zeile doppelt senden (kein Claim). Das hat keine Sicherheitswirkung, kann aber doppelte Nachrichten erzeugen.

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
