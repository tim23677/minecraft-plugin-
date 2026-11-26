# OrderHub: Architektur

Basis-Paket `dev.orderhub`. Verbindlich ist der Quellcode, dieses Dokument beschreibt Schichten, Verträge und Entscheidungen.

## Schichten und Abhängigkeitsrichtung

```
command, gui, placeholder, integration   (Bukkit-nah, nur auf dem Server-Thread)
        |
     service  (OrderService, DeliveryService, ClaimService, AdminService, MarketService, ...)
        |
domain, money, tx, market, statistics   (reines Java, kein org.bukkit)
        |
     storage (Ports + JDBC-Implementierung), economy (Port + Vault-Adapter), item (Codec, Matcher)
```

Regel: `domain`, `money`, `tx`, `market`, `statistics`, `storage` und `config` importieren kein `org.bukkit`. Die Tests erzwingen das (`ArchitectureTest`). Bukkit-Typen kommen nur in `item`, `gui`, `command`, `service` (dünner Teil), `scheduler`, `integration`, `placeholder`, `api.event`.

## Threading

- Datenbankzugriff läuft ausschließlich über `Storage.transact(...)`. Das führt die Arbeit auf einem begrenzten DB-Executor aus, nie auf Server-, Region- oder Entity-Threads.
- Ergebnisse kehren über `PluginScheduler` in den passenden Kontext zurück: `runOn(player, ...)` für Inventaroperationen, `runGlobal` für globale Zustände, `runAsync` für I/O.
- Bukkit-Objekte (`Player`, `ItemStack`, `Inventory`) werden nie in DB-Tasks gereicht. Über Threads gehen nur UUIDs, Records und `byte[]`.
- Offline-Spieler sind Normalfall: Jeder Rückweg prüft `player.isOnline()` im Entity-Kontext und fällt sonst auf persistente Warteschlangen zurück (Claims, Returns, Benachrichtigungen).

## Storage

`Storage.transact(UnitOfWork -> T)` öffnet eine Verbindung, startet eine Transaktion (SQLite: `BEGIN IMMEDIATE`; MariaDB: `REPEATABLE READ` mit `SELECT ... FOR UPDATE` in `findForUpdate`), führt die Arbeit aus, committet oder rollt zurück und wiederholt bei Deadlock oder Serialisierungsfehler mit begrenztem Backoff. Die Repositories sind synchron, weil sie nur innerhalb einer Unit of Work auf dem DB-Thread laufen.

Optimistic Locking: Jede veränderliche Zeile hat `version`. Updates sind Compare-and-Set (`WHERE id=? AND version=?`).

## Transaction Engine

Jede wirtschaftliche Operation ist ein `JournalEntry` mit Idempotency Key (UNIQUE). Zustandsübergänge laufen nur über `TxState.canTransitionTo` und CAS-Updates auf `(id, state, version)`.

Lieferung (`DELIVERY`):

1. `CREATED`: Journal-Eintrag mit Item-Snapshot und Idempotency Key (`delivery:<orderId>:<player>:<nonce>`).
2. `PREPARED`: Auftrag gelockt und geprüft (aktiv, Restmenge, Mindestmenge, Version). Noch keine Mengenänderung.
3. `ITEMS_REMOVED`: Der Delivery-Service hat die Items aus dem Menü-Inventar entfernt (Entity-Kontext). Der Journal-Snapshot erlaubt Rückgabe an den Lieferanten.
4. `COMMITTED`: Eine einzige DB-Transaktion erhöht `quantity_filled`, senkt `escrow_remaining` um `unitPrice * n`, schreibt Claim-Zeile für den Auftraggeber, Ledger-Einträge (Bruttoauszahlung, Steuer) und die Auszahlungs-Warteschlange (`PAYOUT_PENDING`).
5. `PAYOUT_PENDING` → `PAYOUT_IN_PROGRESS` → `PAYOUT_COMPLETED`: Der Payout-Worker beansprucht die Zeile per CAS, ruft den `EconomyBridge` und bucht den Erfolg. `DELIVERY_STORED`/`CLAIM_*` beschreiben den Item-Weg zum Auftraggeber.

Recovery beim Start (`RecoveryService`): `CREATED`/`PREPARED` → `FAILED`, Items waren nie entfernt. `ITEMS_REMOVED` ohne Commit → Items per `item_returns` an den Lieferanten, `FAILED`. `COMMITTED`/`PAYOUT_PENDING` → Payout normal fortsetzen. `PAYOUT_IN_PROGRESS` → `MANUAL_REVIEW`, weil nicht feststellbar ist, ob Vault gezahlt hat. `CLAIM_PENDING` → Claim-Zeile prüfen und freigeben. Jeder Lauf schreibt einen Recovery-Bericht in `audit_log`.

Grenze: Zwischen erfolgreichem Vault-Aufruf und dem Speichern von `PAYOUT_COMPLETED` kann ein Absturz liegen. Dieser Fall wird nie automatisch wiederholt.

## Economy

`EconomyBridge` (Port) arbeitet in Minor Units. Der Vault-Adapter konvertiert über `Money.toDouble`/`Money.fromDouble` mit Prüfung auf NaN, Infinity, Überlauf und Rundung. Escrow ist eine OrderHub-interne Buchung: Beim Erstellen wird dem Auftraggeber der Betrag über Vault abgebucht (`withdraw`), `escrow_remaining` im Auftrag hält die Verpflichtung. Rückerstattungen (Storno, Ablauf) laufen über die Refund-Queue.

## Multi-Server

Gemeinsame MariaDB/MySQL. Jede Instanz hat `server-id`. Cache-Invalidierung über `outbox`-Tabelle (`channel`, `payload`, `id` aufsteigend), Polling im Konfigurationsintervall, Cursor pro Server in `server_registry`. Der Cache ist nie Wahrheit: Mutierende Operationen lesen immer in der Transaktion.

## Items

`ItemCodec` serialisiert Einzelitems (Menge 1) über `ItemStack.serializeAsBytes()` in ein versioniertes Format `[magic 'OHI1'][formatVersion][dataVersion][payload]` und prüft beim Laden Magic, Version, Größenlimit und Material. `ItemMatcher` vergleicht kanonisch (Material + zulässige Komponenten). Sperrliste und Container-Regeln stehen in `item`.

## GUI

`Menu`-Basis mit eigenem `InventoryHolder`, zentralem Listener, der alle Klicktypen abfängt und nur explizit freigegebene Slots zulässt. Menüs sind Klassen, Layout und Texte kommen aus `menus.yml` und `lang`.

## API

`dev.orderhub.api` enthält Interfaces und Records. Einziger Bukkit-Typ ist `org.bukkit.inventory.ItemStack` in `OrderService.CreateRequest` (wird beim Aufruf kopiert und serverseitig geprüft). Zugriff über Bukkit `ServicesManager`. Events (`OrderCreateEvent` usw.) liegen in `dev.orderhub.api.event`.

Details zu Datenbank: DATABASE.md. Sicherheit: SECURITY.md. Entscheidungen: `docs/adr/`.
