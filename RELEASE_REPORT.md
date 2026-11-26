# OrderHub Release-Bericht

Stand: 2026-11-26, Version 1.0.0, Branch `claude/practical-cori-vbbwgd`.

## Ergebnis

Alle Prüfungen, die in dieser Umgebung ausführbar waren, sind grün (siehe Tabelle). Folgende Punkte des Auftrags sind NICHT erledigt: A2 (neue MockBukkit-Tests für DeliveryFlow, CreateFlow und ParcelFlow), B13 (Menüs für Statistik, Verlauf, Favoriten), C15 (Spieler-Szenario auf dem echten Server mit Vault und EssentialsX), C17 (Dependency-Check). Wer das Plugin verkauft, sollte vorher ein Spieler-Szenario mit der eigenen Economy auf einem Testserver ausführen. Das Plugin ist für den Verkauf nur mit dieser Einschränkung freigegeben, die auch in README.md, LISTING.md und COMPATIBILITY.md steht.

## Was tatsächlich gelaufen ist

| Prüfung | Befehl | Ergebnis |
|---|---|---|
| Kompilierung mit `-Xlint:all -Werror`, Spotless, SpotBugs (Main und Test), Tests, Jar | `./gradlew clean build -x dependencyCheckAnalyze --max-workers=1` | erfolgreich, 815 Tests, 0 Fehler, 0 übersprungen |
| Integrationstests gegen echte MariaDB 11.4 (Testcontainers, Docker-Daemon in der Sitzung gestartet, Image über mirror.gcr.io) | `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew integrationTest --max-workers=1` | 49 bestanden, 1 übersprungen (SQLite-Test), 0 Fehler |
| Lasttest SQLite, 100.000 Aufträge, 480 Suchen auf 8 Threads | `./gradlew loadTest --max-workers=1` | p50 19 ms, p95 163 ms, Maximum 1.246 ms (Ausgangswert dieser Sitzung: p50 237 ms, p95 1.026 ms, Maximum 8.456 ms) |
| Echter Server, Paper 1.21.11 (Build 132) und Folia 1.21.11 (Build 14) | `ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh` | 2 bestanden in 86,5 s. Plugin lädt, die drei Bibliotheken kommen über den Paper-Library-Loader, `OrderHub enabled`, Recovery ohne Fehler, Konsolenbefehl antwortet, keine SLF4J-, Nag- oder ERROR-Zeilen |

Die EULA-Zustimmung hat der Nutzer im Auftrag ausdrücklich erteilt. Gradle bekam über den Proxy bei parallelen Downloads HTTP 429, daher `--max-workers=1`. Docker und Tests gleichzeitig führten zu "unable to create native thread" in der Sandbox, deshalb liefen Build und Integrationstests nacheinander.

## Umgesetzt

- A1: Rückgabe-Reservierung `delivery_holds` (Migration V2). `prepare` legt sie im selben Schritt wie PREPARED an und hält alle Items im Menü, auch den Überschuss. Commit verbraucht sie und macht den Überschuss zu einer persistenten Rückgabe, abgelehnter Commit, Abbruch durch Recovery und Absturz geben alles zurück. Tests mit Absturz nach prepare, nach Bestätigung und nach Commit, jeweils mit zweifacher Recovery (`DeliveryHoldTest`, Vertragstest gegen SQLite und MariaDB). Unklarer Ausgang von `prepare` im Flow: ein Tombstone-Eintrag unter demselben Schlüssel (`closeAttempt`) schließt den Versuch atomar ab, erst danach gehen Items direkt zurück. Schlägt das fehl, bleiben die Items im Menü und der Abschluss wird sechsmal wiederholt (fail closed). Ein Review während der Sitzung hat diese beiden Stellen als dupe-gefährdet gemeldet, sie wurden behoben. Ein dritter Befund (Rückgabe der kanonischen Vorlage statt des tatsächlichen Stacks) betrifft nur Stacks, die der Matcher als gleichwertig einstuft, und folgt dem bisherigen Verhalten für gelieferte Items. Keine Änderung.
- A3: `createOrder` der API prüft `orderhub.create`, das Limit aus `orderhub.limit.*` und `orderhub.bypass.fee` wie der Befehl. Offline-Auftraggeber werden ohne `CreateOptions(bypassChecks=true)` abgelehnt, mit Bypass gelten Standardlimit und volle Gebühr. Das aufrufende Plugin steht im Audit-Log (`api.order.create`, Akteur `plugin:<Name>`). Neue Statuswerte `NO_PERMISSION` und `FEE_CHANGED`. Test `ApiCreateOrderTest` (MockBukkit).
- A4: Audit-Eintrag in derselben Transaktion für `cancel`, `purge-expired` (je Auftrag) und `review resolve`, `reload` wird auditiert. Ein fehlschlagender Audit rollt die Stornierung zurück (Test). `recovery` schreibt einen Zusammenfassungseintrag nach dem Lauf, weil ein Lauf viele Transaktionen umfasst (jeder geänderte Journal-Eintrag trägt seinen eigenen Detailtext).
- A5: Discord-Outbox mit bedingtem Claim (`claimed_by`, `claimed_until`, Migration V3). Test mit zwei Workern auf einer Datenbank (jede Nachricht genau einmal) und Vertragstest gegen SQLite und MariaDB.
- A6: Gebühren, Limits und Ablaufzeit liegen als unveränderlicher Schnappschuss (`Rates`), den `reload` atomar austauscht. Geänderte Gebühren wirken ohne Neustart, die bestätigte Gebühr wird gegen die abgerechnete geprüft (`FEE_CHANGED`). Test `FeeConsistencyTest`.
- B7: `orders.create-cooldown-seconds` ist ein Rate Limit (Test `CreateCooldownTest`). Entfernt wurden `cache.sync-interval-seconds`, `market.recommendation-window-days`, `notifications.batch-window-seconds`, `reputation.enabled`, `sounds.*`, `commands.aliases`. Beim Start warnt OrderHub vor unbekannten oder entfernten Schlüsseln. CONFIGURATION.md ist angeglichen. Nicht umgesetzt: Versionsauswertung und Migrationsregeln im `ConfigUpdater` (er ergänzt weiterhin nur fehlende Schlüssel).
- B8: Mehrserverbetrieb ohne serverübergreifenden Cache, die Datenbank ist die Wahrheit. Der einzige Cache ist der Platzhalter-Cache. In CONFIGURATION.md und LISTING.md so beschrieben.
- B9, B10: Hikari, SQLite- und MariaDB-Treiber laden über `libraries` in plugin.yml (Paper-Library-Loader, Maven Central), SLF4J kommt von Paper. Das Jar sank von 13,7 MB auf 0,5 MB, es gibt keine SLF4J-Zeilen mehr. Folge: Der erste Start braucht Zugriff auf Maven Central (README, LISTING, COMPATIBILITY).
- B11: Migration V4 mit Indizes für alle fünf Sortierungen und berechneten Spalten. SQLite p95 von 1.026 auf 163 ms. Es gibt weder Keyset-Pagination noch einen MariaDB-Lasttest.
- B12: Gegen MySQL bricht OrderHub mit der Meldung "MySQL is not supported" ab (Test).
- B14: Die Admin-Ausgaben laufen über `messages_en.yml` und `messages_de.yml`. Ein bestehender Test erzwingt gleiche Schlüssel in beiden Dateien. In `src/main` gibt es keine fest codierten Spielertexte mehr außer Teilen der API-Detailtexte (Statuscodes).
- B13 (Teil): Nicht umgesetzte Funktionen stehen nicht mehr in der Konfiguration und sind im Listing als nicht enthalten genannt.
- D18 bis D20: Version 1.0.0, CHANGELOG.md, README.md, LISTING.md, COMPATIBILITY.md, TESTPLAN.md und dieser Bericht.

## Nicht ausführbar oder nicht ausgeführt

- C15: Auftrag, Lieferung, Auszahlung, Abholung, Ablauf und Neustart mitten in einer Lieferung mit Vault und EssentialsX auf echtem Paper und Folia. Dafür fehlen Bot-Spieler, ein Economy-Plugin und GUI-Automatisierung. Nicht begonnen. Die Absturzpfade sind nur durch Simulation (`SimulatedCrash`, Neustart des Test-Harness) belegt.
- C16 (Teil): Integrationstests gegen MariaDB 11.4 liefen. Ein Test mit zwei vollständigen Plugin-Instanzen an einer Datenbank gibt es nicht. Zwei Storage-Instanzen und zwei Discord-Worker sind getestet.
- C17: `dependencyCheckAnalyze` braucht `NVD_API_KEY`, in der Umgebung gab es keinen. Nicht ausgeführt. Der CI-Job existiert.
- A2: Es gibt keine neuen Tests für DeliveryFlow, CreateFlow und ParcelFlow. Bestehende MockBukkit-Tests decken das GUI-Framework (Doppelklick, Shift-Klick, Drag, Schließen, Disconnect, volles Inventar) und die Befehle ab. Economy-Timeout und Datenbankfehler im Flow sind nur auf Engine-Ebene getestet.
- B13: Menüs für Statistik, Verlauf und Favoriten fehlen, die Ausgabe bleibt im Chat.
- MariaDB 10.11 wurde nicht erneut getestet. PIT und SpotBugs-Erweiterungen liefen nicht.

## Bekannte Restrisiken

- Absturz zwischen erfolgreicher Vault-Zahlung und gespeichertem Ergebnis bleibt ein Fall für MANUAL_REVIEW (keine Exactly-once-Garantie, nie automatisch wiederholt). Mit `/orderadmin review resolve` ist der Fall auflösbar.
- Läuft die Datenbank mehr als etwa eine Minute nicht, nachdem `prepare` einen unklaren Ausgang hatte, bleiben die Items des Lieferanten ungelöst im Menü und werden nur im Log gemeldet (SEVERE). Es entsteht kein Dupe, aber ein Verlust, der manuell ausgeglichen werden muss.
- Schlägt der Spieler-Ablauf in der kurzen Zeit nach dem Entnehmen der Items und vor dem Commit fehl, entscheidet die Recovery anhand des Journals. Sie läuft beim Start und periodisch mit zwei Minuten Wartezeit.
- Vault-Aufrufe laufen auf dem globalen Thread. Ein langsamer Provider blockiert dort bis zu `economy.timeout-ms`.
- Der Plugin-Start braucht beim ersten Mal Maven Central. Auf Servern ohne Internet muss der `libraries`-Ordner von Hand kopiert werden.
- Die Cooldown-Tabelle im Speicher wächst mit der Zahl der Spieler, die je erstellt haben, bis zum Neustart.
- Die Suchleistung auf MariaDB ist nicht gemessen. Ausreißer über 1 s kamen auch im SQLite-Lauf vor (Maximum 1.246 ms).
- Paper API ist ein Snapshot (`1.21.11-R0.1-SNAPSHOT`), Builds sind nicht bitgleich reproduzierbar.
