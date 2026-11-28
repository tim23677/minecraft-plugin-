-- OrderHub V1: Kernschema fuer SQLite und MariaDB/MySQL.
-- Statements werden durch eine Zeile "-- @@" getrennt.
-- Platzhalter: ${AUTO_ID}, ${BLOB}, ${TABLE_OPTS}.

CREATE TABLE IF NOT EXISTS orders (
  id ${AUTO_ID},
  owner_uuid CHAR(36) NOT NULL,
  server_id VARCHAR(64) NOT NULL,
  item_key VARCHAR(190) NOT NULL,
  unit_price BIGINT NOT NULL,
  quantity_total INT NOT NULL,
  quantity_filled INT NOT NULL,
  min_delivery INT NOT NULL,
  escrow_remaining BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  version BIGINT NOT NULL,
  auto_renew SMALLINT NOT NULL,
  category VARCHAR(64) NOT NULL,
  CONSTRAINT ck_orders_filled CHECK (quantity_filled BETWEEN 0 AND quantity_total),
  CONSTRAINT ck_orders_total CHECK (quantity_total > 0),
  CONSTRAINT ck_orders_min CHECK (min_delivery >= 1),
  CONSTRAINT ck_orders_escrow CHECK (escrow_remaining >= 0),
  CONSTRAINT ck_orders_price CHECK (unit_price >= 0),
  CONSTRAINT ck_orders_renew CHECK (auto_renew IN (0, 1)),
  CONSTRAINT ck_orders_status CHECK (status IN
    ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED'))
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_orders_market ON orders (status, item_key, unit_price)
-- @@
CREATE INDEX IF NOT EXISTS ix_orders_owner ON orders (owner_uuid, status)
-- @@
CREATE INDEX IF NOT EXISTS ix_orders_expiry ON orders (status, expires_at)
-- @@
CREATE TABLE IF NOT EXISTS order_items (
  order_id BIGINT NOT NULL PRIMARY KEY,
  item_key VARCHAR(190) NOT NULL,
  format_version INT NOT NULL,
  item_data ${BLOB} NOT NULL,
  CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ${TABLE_OPTS}
-- @@
CREATE TABLE IF NOT EXISTS order_deliveries (
  id ${AUTO_ID},
  order_id BIGINT NOT NULL,
  supplier_uuid CHAR(36) NOT NULL,
  quantity INT NOT NULL,
  amount BIGINT NOT NULL,
  journal_id CHAR(36) NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT uq_order_deliveries_journal UNIQUE (journal_id),
  CONSTRAINT ck_order_deliveries_qty CHECK (quantity > 0),
  CONSTRAINT ck_order_deliveries_amount CHECK (amount >= 0),
  CONSTRAINT fk_order_deliveries_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_order_deliveries_order ON order_deliveries (order_id)
-- @@
CREATE INDEX IF NOT EXISTS ix_order_deliveries_supplier
  ON order_deliveries (supplier_uuid, created_at)
-- @@
CREATE TABLE IF NOT EXISTS escrow_accounts (
  player_uuid CHAR(36) NOT NULL PRIMARY KEY,
  reserved BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT ck_escrow_accounts_reserved CHECK (reserved >= 0)
) ${TABLE_OPTS}
-- @@
CREATE TABLE IF NOT EXISTS transactions (
  seq ${AUTO_ID},
  id CHAR(36) NOT NULL,
  tx_id CHAR(36) NOT NULL,
  player_uuid CHAR(36) NULL,
  kind VARCHAR(24) NOT NULL,
  amount BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  note VARCHAR(500) NULL,
  CONSTRAINT uq_transactions_id UNIQUE (id),
  CONSTRAINT ck_transactions_amount CHECK (amount >= 0),
  CONSTRAINT ck_transactions_kind CHECK (kind IN
    ('ESCROW_RESERVE', 'CREATE_FEE', 'PAYOUT', 'SALES_TAX', 'REFUND',
     'ADMIN_ADJUST', 'REVERSAL', 'FAILED'))
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_transactions_tx ON transactions (tx_id)
-- @@
CREATE INDEX IF NOT EXISTS ix_transactions_player ON transactions (player_uuid, created_at)
-- @@
CREATE TABLE IF NOT EXISTS transaction_journal (
  id CHAR(36) NOT NULL PRIMARY KEY,
  idempotency_key VARCHAR(190) NOT NULL,
  order_id BIGINT NULL,
  player_uuid CHAR(36) NOT NULL,
  server_id VARCHAR(64) NOT NULL,
  tx_type VARCHAR(32) NOT NULL,
  item_key VARCHAR(190) NULL,
  item_format_version INT NULL,
  item_data ${BLOB} NULL,
  quantity INT NOT NULL,
  amount BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  detail VARCHAR(1000) NULL,
  CONSTRAINT uq_journal_idempotency UNIQUE (idempotency_key),
  CONSTRAINT ck_journal_quantity CHECK (quantity >= 0),
  CONSTRAINT ck_journal_amount CHECK (amount >= 0)
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_journal_state ON transaction_journal (state, updated_at)
-- @@
CREATE INDEX IF NOT EXISTS ix_journal_player ON transaction_journal (player_uuid, created_at)
-- @@
CREATE INDEX IF NOT EXISTS ix_journal_order ON transaction_journal (order_id)
-- @@
CREATE TABLE IF NOT EXISTS payout_queue (
  id CHAR(36) NOT NULL PRIMARY KEY,
  tx_id CHAR(36) NOT NULL,
  player_uuid CHAR(36) NOT NULL,
  amount BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL,
  review_reason VARCHAR(500) NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT uq_payout_queue_tx UNIQUE (tx_id),
  CONSTRAINT ck_payout_queue_amount CHECK (amount >= 0),
  CONSTRAINT ck_payout_queue_state CHECK (state IN
    ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'REVIEW'))
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_payout_queue_state ON payout_queue (state, created_at)
-- @@
CREATE TABLE IF NOT EXISTS refund_queue (
  id CHAR(36) NOT NULL PRIMARY KEY,
  tx_id CHAR(36) NOT NULL,
  player_uuid CHAR(36) NOT NULL,
  amount BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL,
  review_reason VARCHAR(500) NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT uq_refund_queue_tx UNIQUE (tx_id),
  CONSTRAINT ck_refund_queue_amount CHECK (amount >= 0),
  CONSTRAINT ck_refund_queue_state CHECK (state IN
    ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'REVIEW'))
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_refund_queue_state ON refund_queue (state, created_at)
-- @@
CREATE TABLE IF NOT EXISTS item_claims (
  id ${AUTO_ID},
  owner_uuid CHAR(36) NOT NULL,
  order_id BIGINT NULL,
  item_key VARCHAR(190) NOT NULL,
  item_format_version INT NOT NULL,
  item_data ${BLOB} NOT NULL,
  quantity INT NOT NULL,
  state VARCHAR(16) NOT NULL,
  reserved_by CHAR(36) NULL,
  source_tx CHAR(36) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT uq_item_claims_source UNIQUE (source_tx, owner_uuid),
  CONSTRAINT ck_item_claims_qty CHECK (quantity >= 0),
  CONSTRAINT ck_item_claims_state CHECK (state IN ('AVAILABLE', 'RESERVED', 'CLAIMED')),
  CONSTRAINT fk_item_claims_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_item_claims_owner ON item_claims (owner_uuid, state, id)
-- @@
CREATE INDEX IF NOT EXISTS ix_item_claims_reserved ON item_claims (state, updated_at)
-- @@
CREATE TABLE IF NOT EXISTS item_returns (
  id ${AUTO_ID},
  owner_uuid CHAR(36) NOT NULL,
  order_id BIGINT NULL,
  item_key VARCHAR(190) NOT NULL,
  item_format_version INT NOT NULL,
  item_data ${BLOB} NOT NULL,
  quantity INT NOT NULL,
  state VARCHAR(16) NOT NULL,
  reserved_by CHAR(36) NULL,
  source_tx CHAR(36) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT uq_item_returns_source UNIQUE (source_tx, owner_uuid),
  CONSTRAINT ck_item_returns_qty CHECK (quantity >= 0),
  CONSTRAINT ck_item_returns_state CHECK (state IN ('AVAILABLE', 'RESERVED', 'CLAIMED'))
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_item_returns_owner ON item_returns (owner_uuid, state, id)
-- @@
CREATE INDEX IF NOT EXISTS ix_item_returns_reserved ON item_returns (state, updated_at)
-- @@
CREATE TABLE IF NOT EXISTS player_statistics (
  player_uuid CHAR(36) NOT NULL PRIMARY KEY,
  orders_created BIGINT NOT NULL DEFAULT 0,
  orders_completed BIGINT NOT NULL DEFAULT 0,
  items_delivered BIGINT NOT NULL DEFAULT 0,
  items_received BIGINT NOT NULL DEFAULT 0,
  money_earned BIGINT NOT NULL DEFAULT 0,
  money_spent BIGINT NOT NULL DEFAULT 0,
  last_active_at BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_player_statistics_nonneg CHECK (
    orders_created >= 0 AND orders_completed >= 0 AND items_delivered >= 0
    AND items_received >= 0 AND money_earned >= 0 AND money_spent >= 0)
) ${TABLE_OPTS}
-- @@
CREATE TABLE IF NOT EXISTS player_favorites (
  id ${AUTO_ID},
  player_uuid CHAR(36) NOT NULL,
  item_key VARCHAR(190) NOT NULL,
  alert_below_price BIGINT NULL,
  last_alert_at BIGINT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT uq_player_favorites UNIQUE (player_uuid, item_key),
  CONSTRAINT ck_player_favorites_alert CHECK (alert_below_price IS NULL OR alert_below_price > 0)
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_player_favorites_item ON player_favorites (item_key)
-- @@
CREATE TABLE IF NOT EXISTS price_history (
  id ${AUTO_ID},
  journal_id CHAR(36) NOT NULL,
  order_id BIGINT NOT NULL,
  item_key VARCHAR(190) NOT NULL,
  unit_price BIGINT NOT NULL,
  quantity INT NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT uq_price_history_journal UNIQUE (journal_id),
  CONSTRAINT ck_price_history_price CHECK (unit_price > 0),
  CONSTRAINT ck_price_history_qty CHECK (quantity > 0)
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_price_history_item ON price_history (item_key, created_at)
-- @@
CREATE INDEX IF NOT EXISTS ix_price_history_created ON price_history (created_at)
-- @@
CREATE TABLE IF NOT EXISTS market_snapshots (
  id ${AUTO_ID},
  item_key VARCHAR(190) NOT NULL,
  bucket_start BIGINT NOT NULL,
  trades INT NOT NULL,
  volume BIGINT NOT NULL,
  min_price BIGINT NOT NULL,
  max_price BIGINT NOT NULL,
  median_price BIGINT NOT NULL,
  avg_price BIGINT NOT NULL,
  open_orders INT NOT NULL DEFAULT 0,
  CONSTRAINT uq_market_snapshots UNIQUE (item_key, bucket_start),
  CONSTRAINT ck_market_snapshots_range CHECK (min_price <= max_price AND trades >= 0)
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_market_snapshots_bucket ON market_snapshots (bucket_start)
-- @@
CREATE TABLE IF NOT EXISTS notifications (
  id ${AUTO_ID},
  player_uuid CHAR(36) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  payload VARCHAR(2000) NOT NULL,
  dedupe_key VARCHAR(190) NULL,
  created_at BIGINT NOT NULL,
  delivered_at BIGINT NULL,
  CONSTRAINT uq_notifications_dedupe UNIQUE (player_uuid, dedupe_key)
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_notifications_pending ON notifications (player_uuid, delivered_at)
-- @@
CREATE TABLE IF NOT EXISTS discord_outbox (
  id ${AUTO_ID},
  payload TEXT NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at BIGINT NOT NULL,
  last_error VARCHAR(500) NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT ck_discord_outbox_state CHECK (state IN ('PENDING', 'SENT', 'FAILED'))
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_discord_outbox_due ON discord_outbox (state, next_attempt_at)
-- @@
CREATE TABLE IF NOT EXISTS cache_outbox (
  id ${AUTO_ID},
  channel VARCHAR(64) NOT NULL,
  payload VARCHAR(2000) NOT NULL,
  origin_server VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_cache_outbox_created ON cache_outbox (created_at)
-- @@
CREATE TABLE IF NOT EXISTS server_registry (
  server_id VARCHAR(64) NOT NULL PRIMARY KEY,
  started_at BIGINT NOT NULL,
  heartbeat_at BIGINT NOT NULL,
  outbox_cursor BIGINT NOT NULL DEFAULT 0,
  plugin_version VARCHAR(32) NULL
) ${TABLE_OPTS}
-- @@
CREATE TABLE IF NOT EXISTS audit_log (
  id ${AUTO_ID},
  actor VARCHAR(64) NOT NULL,
  action VARCHAR(64) NOT NULL,
  target VARCHAR(190) NULL,
  reason VARCHAR(500) NULL,
  detail TEXT NULL,
  server_id VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL
) ${TABLE_OPTS}
-- @@
CREATE INDEX IF NOT EXISTS ix_audit_log_created ON audit_log (created_at)
-- @@
CREATE INDEX IF NOT EXISTS ix_audit_log_action ON audit_log (action, created_at)
