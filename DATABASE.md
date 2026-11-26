# OrderHub: Datenbank

Zwei Dialekte: SQLite (Einzelserver, Standard) und MariaDB/MySQL (Netzwerk). Verbindungspool über HikariCP, Zugriff nur über `Storage.transact`. Migrationen sind versionierte SQL-Dateien unter `src/main/resources/db/migration/{common,sqlite,mariadb}/V<nr>__<name>.sql`. Platzhalter `${AUTO_ID}` (SQLite `INTEGER PRIMARY KEY AUTOINCREMENT`, MariaDB `BIGINT PRIMARY KEY AUTO_INCREMENT`) und `${BLOB}` (BLOB, LONGBLOB). Angewendete Versionen stehen mit SHA-256 der Datei in `migration_history`. Ein veränderter Hash einer bereits angewendeten Migration bricht den Start ab.

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
| `migration_history` | Angewendete Migrationen |
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
