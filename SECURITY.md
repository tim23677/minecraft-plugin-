# OrderHub: Sicherheit

Dieses Dokument enthält Bedrohungsmodell und Testmatrix. Spalte "Testergebnis" wird erst gefüllt, wenn der Test tatsächlich gelaufen ist; bis dahin steht dort "offen". Die finale Prüfung (20 Kategorien) steht in RELEASE_REPORT.md.

## Grundsätze

- Der Client ist nie vertrauenswürdig: Itemzustand, Menge und Preis werden serverseitig geprüft.
- Geld und Items bewegen sich nur über das Journal mit Idempotency Key.
- Unklare Zustände werden nie automatisch wiederholt, sondern nach MANUAL_REVIEW eskaliert.
- Keine Telemetrie, keine Online-Lizenzprüfung, keine Obfuskation.
- Zugangsdaten (Datenbankpasswort, Webhook-URL) stehen nie in Dateien, die Passwörter kommen aus Umgebungsvariablen (`storage.mariadb.password-env`, `ORDERHUB_DISCORD_WEBHOOK`), und werden nie geloggt und werden nie geloggt.

## Testmatrix

Ergebnisse stammen aus dem Lauf vom 2026-11-26: `./gradlew build` (722 Tests, 0 Fehler, 0 übersprungen) und `./gradlew integrationTest` gegen eine echte MariaDB 10.11 (44 Tests, 0 Fehler). "bestanden" heißt: der genannte Test lief und bestand. Das beweist keine Abwesenheit von Fehlern.

| ID | Bedrohung | Komponente | Schutz | Test | Ergebnis |
|---|---|---|---|---|---|
| S1 | Item-Dupe durch GUI-Klick (Shift, Zahlentaste, Offhand, Drag, Doppelklick), Schließen während laufender Lieferung | gui | Zentraler Listener, nur freigegebene Slots, eingefrorene Menüs geben beim Schließen nichts heraus | GuiSecurityTest (53), MenuLifecycleTest, MenuDisableTest, MenuFreezeTest | bestanden |
| S2 | Item-Dupe durch Container-Inhalt | item | Matcher lehnt Container und Bundles ab | ItemMatcherTest (35), BlocklistTest | bestanden |
| S3 | Money-Dupe durch doppelte Auszahlung | tx | Idempotency Key, UNIQUE(tx_id), CAS auf Queue und Journal | PayoutWorkerTest (u. a. paysOnceAndIsIdempotentOnRerun, manyWorkersNeverPayTwice) | bestanden |
| S4 | Doppelte Lieferung bei gleichzeitigen Requests | tx, storage | Version-CAS, FOR UPDATE | DeliveryEngineTest, StorageContractTest (concurrentDeliveriesAcrossTwoServersLoseNoUpdate) auf SQLite und MariaDB | bestanden |
| S5 | Absturz nach Vault-Auszahlung | tx | PAYOUT_IN_PROGRESS wird MANUAL_REVIEW, nie automatisch wiederholt | PayoutWorkerTest (crashDuringDepositIsEscalatedByRecovery, unknownOutcome...) | bestanden |
| S6 | Absturz nach Item-Entnahme oder Übergabe | tx | Journal-Snapshot, Return-Queue, Übergabe-Marker | RecoveryServiceTest, ParcelEngineTest (crashBeforeHandover..., crashAfterHandoverIsNeverReleasedAutomatically) | bestanden |
| S7 | SQL-Injection | storage | Nur PreparedStatement, dynamische Teile nur aus Enums und Konstanten | StorageContractTest (Injection über itemKey, category, Idempotency Key) auf SQLite und MariaDB, Review der Jdbc-Klassen | bestanden |
| S8 | Manipulierte Itemdaten in der DB | item | Codec prüft Magic, Version, Größe, Material | ItemCodecTest (19) | bestanden |
| S9 | Überlauf, NaN, Infinity bei Preisen | money | Money-Parser und Exact-Arithmetik | OrderServiceTest (overflowingAmountIsRejectedBeforeAnyWithdraw), SettingsTest, ConfigRecordsTest | bestanden (kein eigener MoneyTest) |
| S10 | Unberechtigte Admin-Befehle | command | Permission je Unterbefehl, Bestätigung, Begründung, Audit-Log | OrderCommandTest (adminCommandsNeedPermission, adminCancelNeedsConfirmAndReason) | teilweise: Audit wird nach der Aktion in eigener Transaktion geschrieben, reload ist nicht auditiert |
| S11 | Veralteter Cache | storage | Mutationen lesen in der Transaktion | PlaceholderCacheTest | Cache-Outbox-Polling ist nicht implementiert, siehe RELEASE_REPORT.md |
| S12 | Zwei Server bearbeiten denselben Auftrag, Limit-Umgehung mit altem Snapshot | storage | Version-CAS, Row-Lock, gesperrtes Zählen | StorageContractTest (ownerLimitHoldsWhenTheTransactionAlreadyReadBeforeLocking, lockOwnerSerializesCountThenInsertAcrossServers) | bestanden auf SQLite und MariaDB. Der Snapshot-Test schlägt auf MariaDB ohne den Fix fehl und besteht mit dem Fix |
| S13 | Spieler offline während Lieferung/Abholung | flow | Persistente Parcels, Abschluss ohne Spieler | ParcelEngineTest, MenuLifecycleTest | Engine und Menüs getestet, der Bukkit-nahe Ablauf (DeliveryFlow, ParcelFlow) hat keinen eigenen automatisierten Test |
| S14 | API-Missbrauch durch Drittplugins | api | Nur Service-Interfaces, Validierung, Lebensdauer begrenzt | nur Code-Review | offen: kein ApiValidationTest, kein Audit der aufrufenden Plugins |
| S15 | Webhook-Missbrauch (SSRF, Leaks) | discord | Nur https, Host-Allowlist, keine Spielerdaten, Mentions aus | DiscordTest, DiscordWorkerTest | bestanden |
| S16 | Zahlung eskaliert nach MANUAL_REVIEW, obwohl der Provider nie aufgerufen wurde (Lag-Spike auf dem globalen Thread) | economy | Task nie gestartet (ABANDONED): Ergebnis UNAVAILABLE, der PayoutWorker wiederholt mit Backoff. Nur gestartet und nicht beendet bleibt unklar (nie wiederholt) | GlobalThreadEconomyTest (neverStartedTimeout..., startedButUnfinishedCall...), PayoutLagSpikeTest (20 wartende Auszahlungen) | bestanden, 2026-11-27 |
| S17 | Falsches Item wird geliefert, weil "erlaubt" mit "toleriert" verwechselt wird (beschaedigtes Schwert fuer ein neues, Unbreaking-Buch fuer ein Mending-Buch) | item | allow-* gilt nur fuer die Zulassung, der Abgleich ist exakt. Toleranz nur je Auftrag mit Untergrenzen (Haltbarkeit in Prozent, Mindeststufen), nie fuer andere Komponenten | ItemMatcherToleranceTest, ItemMatcherTest (admittedDifferencesStillMustMatchExactly), ItemVariantMatchingTest (je Item-Art exakt) | bestanden, 2026-11-27. Grenze: MockBukkit traegt Komponenten nicht durch die Serialisierung, die Vorlage wird dort direkt uebergeben. Ob PotionContents auf echtem Paper per equals vergleichbar sind, ist hier nicht nachgewiesen |
| S18 | Speicherwachstum durch die Cooldown-Tabelle | flow | Eintrag wird beim Verlassen entfernt und zeitgesteuert bereinigt | CreateCooldownTest (purgeRemovesOnlyExpiredEntries...), CreateFlowTest (theCooldownEntryIsRemovedWhenThePlayerQuits) | bestanden, 2026-11-27 |

## Bekannte Grenzen

- Vault bietet keine Transaktions-ID und keine Abfrage "wurde diese Zahlung ausgeführt". Absturzfenster zwischen Provider-Aufruf und Statusspeicherung bleibt, wird aber nie blind wiederholt.
- Economy-Provider können Zahlungen still verweigern oder runden. OrderHub prüft den Rückgabewert und bucht nur bei bestätigtem Erfolg.
- MANUAL_REVIEW-Einträge (Zahlung mit unklarem Ausgang) haben in dieser Version noch keinen Admin-Befehl zum Auflösen. Ein Administrator muss manuell zahlen und den Fall außerhalb von OrderHub dokumentieren.
- Ein kompromittierter DB-Zugang oder ein bösartiges Server-Plugin kann Daten manipulieren. OrderHub schützt davor nicht.
