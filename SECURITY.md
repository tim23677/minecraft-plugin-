# OrderHub: Sicherheit

Dieses Dokument enthält Bedrohungsmodell und Testmatrix. Spalte "Testergebnis" wird erst gefüllt, wenn der Test tatsächlich gelaufen ist; bis dahin steht dort "offen". Die finale Prüfung (20 Kategorien) steht in RELEASE_REPORT.md.

## Grundsätze

- Der Client ist nie vertrauenswürdig: Itemzustand, Menge und Preis werden serverseitig geprüft.
- Geld und Items bewegen sich nur über das Journal mit Idempotency Key.
- Unklare Zustände werden nie automatisch wiederholt, sondern nach MANUAL_REVIEW eskaliert.
- Keine Telemetrie, keine Online-Lizenzprüfung, keine Obfuskation.
- Zugangsdaten (Datenbankpasswort, Webhook-URL) stehen nur in `database.yml`/`discord.yml` und werden nie geloggt.

## Testmatrix

| ID | Bedrohung | Komponente | Schutz | Test | Ergebnis |
|---|---|---|---|---|---|
| S1 | Item-Dupe durch GUI-Klick (Shift, Zahlentaste, Offhand, Drag, Doppelklick) | gui | Zentraler Listener, nur freigegebene Slots | GuiSecurityTest | offen |
| S2 | Item-Dupe durch Container-Inhalt | item | Matcher lehnt Container und Bundles ab | ItemMatcherTest | offen |
| S3 | Money-Dupe durch doppelte Auszahlung | tx | Idempotency Key, UNIQUE(tx_id), CAS | PayoutIdempotencyTest | offen |
| S4 | Doppelte Lieferung bei gleichzeitigen Requests | tx | Version-CAS, FOR UPDATE | ConcurrentDeliveryTest | offen |
| S5 | Absturz nach Vault-Auszahlung | tx | PAYOUT_IN_PROGRESS nach MANUAL_REVIEW | RecoveryTest | offen |
| S6 | Absturz nach Item-Entnahme | tx | Journal-Snapshot, Return-Queue | RecoveryTest | offen |
| S7 | SQL-Injection | storage | Nur PreparedStatement, keine String-Konkatenation von Nutzereingaben | SqlInjectionTest, Code-Review | offen |
| S8 | Manipulierte Itemdaten in der DB | item | Codec prüft Magic, Version, Größe, Material | ItemCodecTest | offen |
| S9 | Überlauf, NaN, Infinity bei Preisen | money | Money-Parser und Exact-Arithmetik | MoneyTest | offen |
| S10 | Unberechtigte Admin-Befehle | command | Permission je Subbefehl, Bestätigung, Begründung, Audit | AdminCommandTest | offen |
| S11 | Veralteter Cache | storage | Mutationen lesen in der Transaktion, Outbox-Polling | CacheSyncTest | offen |
| S12 | Zwei Server bearbeiten denselben Auftrag | storage | Version-CAS, Row-Lock | MultiServerTest | offen |
| S13 | Spieler offline während Lieferung/Abholung | service | Persistente Parcels, Entity-Kontext-Prüfung | DeliveryOfflineTest | offen |
| S14 | API-Missbrauch durch Drittplugins | api | Nur Service-Interfaces, Validierung wie intern, kein SQL-Zugang | ApiValidationTest | offen |
| S15 | Webhook-Missbrauch (SSRF, Leaks) | discord | Nur https, Host-Allowlist discord.com/discordapp.com, keine Spielerdaten außer Name | DiscordSecurityTest | offen |

## Bekannte Grenzen

- Vault bietet keine Transaktions-ID und keine Abfrage "wurde diese Zahlung ausgeführt". Absturzfenster zwischen Provider-Aufruf und Statusspeicherung bleibt, wird aber nie blind wiederholt.
- Economy-Provider können Zahlungen still verweigern oder runden. OrderHub prüft den Rückgabewert und bucht nur bei bestätigtem Erfolg.
- Ein kompromittierter DB-Zugang oder ein bösartiges Server-Plugin kann Daten manipulieren. OrderHub schützt davor nicht.
