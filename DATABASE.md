# OrderHub: Datenbank

Zwei Dialekte: SQLite (Einzelserver, Standard) und MariaDB (Netzwerk, MySQL wird nicht unterstützt). Verbindungspool über HikariCP, Zugriff nur über `Storage.transact`. Migrationen sind versionierte SQL-Dateien unter `src/main/resources/db/migration/{common,sqlite,mariadb}/V<nr>__<name>.sql`. Platzhalter `${AUTO_ID}` (SQLite `INTEGER PRIMARY KEY AUTOINCREMENT`, MariaDB `BIGINT PRIMARY KEY AUTO_INCREMENT`), `${BLOB}` (BLOB, LONGBLOB) und `${TABLE_OPTS}` (SQLite leer, MariaDB `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin`). Angewendete Versionen stehen mit SHA-256 der Datei in `schema_version`. Ein veränderter Hash einer bereits angewendeten Migration bricht den Start ab.

## Tabellen

| Tabelle | Zweck |
|---|---|
| `orders` | Aufträge, Escrow-Restbetrag, Version |
| `order_items` | Item-Snapshot je Auftrag (itemKey, formatVersion, data), normalisiert aus `orders` |
| `order_deliveries` | Je erfolgreicher Lieferung eine Zeile (Auftrag, Lieferant, Menge, Betrag, Journal-Id) |
| `escrow_accounts` | Je Spieler Summe reservierter Beträge (abgeleitet, wird in derselben Transaktion fortgeschrieben) |
| `transactions` | Unveränderliches Hauptbuch (`LedgerRepository`) |
| `transaction_journal` | Journal mit Zustandsmaschine (`JournalRepository`) |
| `payout_queue` | Auszahlungen (`PayoutRepository`, Kind PAYOUT) |
| `refund_queue` | Rückerstattungen (`PayoutRepository`, Kind REFUND) |
| `item_claims` | Abholbare Items des Auftraggebers (`ParcelRepository`) |
| `item_returns` | Rückgabewarteschlange für Lieferanten (`ParcelRepository`) |
| `player_statistics` | Aggregierte Spielerstatistik |
| `player_favorites` | Favoriten und Preisalarme |
| `price_history` | Abgeschlossene Handelsgeschäfte (nur echte Lieferungen) |
| `market_snapshots` | Stündliche Marktaggregate je Item |
| `notifications` | Persistente Benachrichtigungen |
| `discord_outbox` | Ausgehende Discord-Nachrichten mit Versuchszähler und Backoff |
| `cache_outbox` | Änderungsereignisse für Cache-Invalidierung im Netzwerk |
| `server_registry` | Server-ID, Start, Heartbeat, Outbox-Cursor |
| `schema_version` | Angewendete Migrationen |
| `audit_log` | Admin- und Recovery-Aktionen mit Begründung |

## Kern-Constraints

- `orders.version` steigt bei jedem Update, Updates sind `WHERE id=? AND version=?`.
- `orders`: `CHECK (quantity_filled BETWEEN 0 AND quantity_total)`, `CHECK (escrow_remaining >= 0)`. Zusätzlich Index `(status, item_key, unit_price)` und `(owner_uuid, status)` und `(status, expires_at)`.
- `transaction_journal`: `UNIQUE(idempotency_key)`, Index `(state, updated_at)`, `(player_uuid, created_at)`, `(order_id)`.
- `transactions`: Append-only. Kein UPDATE/DELETE im Code. Trigger verbieten Änderungen (SQLite und MariaDB), Tests prüfen das.
- `payout_queue`/`refund_queue`: `UNIQUE(tx_id)`.
- `item_claims`/`item_returns`: `UNIQUE(source_tx, owner_uuid)`, `CHECK (quantity >= 0)`.
- `price_history`: `UNIQUE(journal_id)`, damit eine Lieferung nie doppelt zählt.
- Fremdschlüssel von `order_deliveries`, `item_claims` auf `orders`, soweit Löschungen nicht vorkommen (Aufträge werden archiviert, nicht gelöscht).

## Item-Serialisierung

Siehe ARCHITECTURE.md, Abschnitt Items. Gespeichert werden nur geprüfte Einzelitems im OrderHub-Format `OHI1`. Beim Laden prüft der Codec Magic, Formatversion, Größe und Material und lehnt unbekannte oder zu große Daten ab. Es werden keine Java-Objekte deserialisiert.

## Aufbewahrung

`price_history` und `market_snapshots` werden nach `retention.history-days` gelöscht (Hintergrundtask in Batches). `transactions`, `transaction_journal` und `audit_log` werden nicht automatisch gelöscht.

## Migrationsrunner

`dev.orderhub.storage.Migrations`. Die Dateien stehen in `db/migration/migrations.list` (ein Jar kann Verzeichnisse nicht zuverlässig auflisten, ein Test prüft, dass die Liste mit den Dateien übereinstimmt). Je Version läuft zuerst die `common`-Datei, dann die des Dialekts. Statements werden durch eine Zeile `-- @@` getrennt (Trigger enthalten Semikolons). `schema_version(version, scope, name, checksum, applied_at)` hat je Datei eine Zeile mit SHA-256 und wird vom Runner selbst angelegt. Ein abweichender Hash einer angewendeten Datei wirft `MigrationException`.

- SQLite: alles in einem `BEGIN IMMEDIATE`, DDL ist transaktional, parallele Starts warten auf `busy_timeout`.
- MariaDB: `GET_LOCK('orderhub_migration', 120)` auf einer Verbindung. DDL committet implizit; die Skripte nutzen `IF NOT EXISTS` bzw. `DROP TRIGGER IF EXISTS`, damit ein abgebrochener Lauf wiederholbar ist. `CREATE INDEX IF NOT EXISTS` gibt es in MariaDB, nicht in MySQL 8: Getestet ist nur MariaDB (10.11), MySQL ist nicht verifiziert.
- Die Append-only-Trigger (`trg_transactions_no_update`, `trg_transactions_no_delete`) brauchen auf MariaDB das Recht `TRIGGER`; bei aktiviertem Binlog kann zusätzlich `log_bin_trust_function_creators=1` nötig sein.

## Kern-Zeilenformate

UUIDs sind `CHAR(36)`, Zeiten `BIGINT` (Millisekunden), Geld `BIGINT` (Minor Units), Booleans `SMALLINT` 0/1, Enums als Text mit `CHECK (... IN (...))`.

- `orders`: `item_key` doppelt in `order_items` (Index auf `orders`), `order_items.format_version/item_data` hält den Snapshot.
- `escrow_accounts.reserved` wird von `OrderRepository.insert/update` in derselben Transaktion um die Escrow-Differenz fortgeschrieben. `OrderRepository.lockOwner(owner)` legt auf MariaDB bei Bedarf eine Zeile mit `reserved = 0` an (`ON DUPLICATE KEY UPDATE` ohne Wirkung) und sperrt sie per `SELECT ... FOR UPDATE`; auf SQLite ohne Wirkung.
- `transactions`: `seq` (Auto-ID, Reihenfolge) und `id` (UNIQUE). `amount >= 0`, Richtung folgt aus `kind`.
- `transaction_journal`: Item-Snapshot als `item_key`, `item_format_version`, `item_data` (alle NULL ohne Item). `detail` wird auf 1000 Zeichen gekürzt.
- `payout_queue`/`refund_queue`: `id`, `tx_id` (UNIQUE), `player_uuid`, `amount`, `state` (PENDING, IN_PROGRESS, COMPLETED, FAILED, REVIEW), `attempts`, `review_reason`, `created_at`, `updated_at`. `claimBatch` ist ein CAS je Zeile (`UPDATE ... WHERE id=? AND state='PENDING'`) und erhöht `attempts`; es beansprucht zuerst Auszahlungen, dann Rückerstattungen.
- `item_claims`/`item_returns`: `state` (AVAILABLE, RESERVED, CLAIMED), `reserved_by`, `source_tx`, `UNIQUE(source_tx, owner_uuid)`.

## Randtabellen (Entwurf, V1)

| Tabelle | Spalten |
|---|---|
| `order_deliveries` | `id`, `order_id` (FK), `supplier_uuid`, `quantity`, `amount`, `journal_id` (UNIQUE), `created_at` |
| `player_statistics` | `player_uuid` (PK), `orders_created`, `orders_completed`, `items_delivered`, `items_received`, `money_earned`, `money_spent`, `last_active_at`, `updated_at` (alle Zähler `>= 0`) |
| `player_favorites` | `id`, `player_uuid`, `item_key`, `alert_below_price` (NULL = kein Alarm), `last_alert_at`, `created_at`, `UNIQUE(player_uuid, item_key)` |
| `price_history` | `id`, `journal_id` (UNIQUE), `order_id`, `item_key`, `unit_price`, `quantity`, `created_at`, Index `(item_key, created_at)` |
| `market_snapshots` | `id`, `item_key`, `bucket_start`, `trades`, `volume`, `min_price`, `max_price`, `median_price`, `avg_price`, `open_orders`, `UNIQUE(item_key, bucket_start)` |
| `notifications` | `id`, `player_uuid`, `kind`, `payload`, `dedupe_key` (Spam-Schutz, `UNIQUE(player_uuid, dedupe_key)`), `created_at`, `delivered_at` (NULL = offen) |
| `discord_outbox` | `id`, `payload`, `state` (PENDING, SENT, FAILED), `attempts`, `next_attempt_at` (Backoff), `last_error`, `created_at`, `updated_at` |
| `cache_outbox` | `id` (aufsteigend, Polling-Cursor), `channel`, `payload`, `origin_server`, `created_at` |
| `server_registry` | `server_id` (PK), `started_at`, `heartbeat_at`, `outbox_cursor`, `plugin_version` |
| `audit_log` | `id`, `actor`, `action`, `target`, `reason`, `detail`, `server_id`, `created_at` |

## Transaktionen und Wiederholung

`JdbcStorage.transact`: SQLite `BEGIN IMMEDIATE` (Pool-Größe 1 je Instanz, WAL, `busy_timeout`, `foreign_keys`), MariaDB `REPEATABLE READ`. Wiederholt wird bei SQLSTATE `40*`, MariaDB-Fehler 1213 und 1205 sowie SQLITE_BUSY/LOCKED, höchstens `maxAttempts` (Standard 5) mit exponentiellem Backoff und Jitter (`baseBackoffMillis` 25, `maxBackoffMillis` 1000). Danach und bei allen anderen Fehlern bricht das Future mit dem Originalfehler ab, die Transaktion ist zurückgerollt. Nach einem Duplicate-Key liest `insertIfAbsent`/`add` mit `FOR UPDATE`, weil ein gewöhnliches Lesen unter REPEATABLE READ die fremde Zeile nicht sieht.
