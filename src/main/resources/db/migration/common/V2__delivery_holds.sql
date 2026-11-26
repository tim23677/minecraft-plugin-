-- Rueckgabe-Reservierung einer Lieferung. Wird im selben Schritt wie PREPARED angelegt und haelt
-- ALLE Items, die der Lieferant ins Menue gelegt hat (auch den Ueberschuss). Zustaende:
--   HELD      Items sind nur im (fluechtigen) Liefermenue, der Journal-Eintrag ist nicht abgeschlossen
--   RELEASED  Items wurden als Rueckgabe (item_returns) an den Lieferanten freigegeben
--   CONSUMED  Items sind verbraucht (Lieferung festgeschrieben) oder gingen direkt zurueck
CREATE TABLE IF NOT EXISTS delivery_holds (
  tx_id CHAR(36) NOT NULL PRIMARY KEY,
  owner_uuid CHAR(36) NOT NULL,
  order_id BIGINT NULL,
  item_key VARCHAR(190) NOT NULL,
  item_format_version INT NOT NULL,
  item_data ${BLOB} NOT NULL,
  quantity INT NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT ck_delivery_holds_qty CHECK (quantity > 0),
  CONSTRAINT ck_delivery_holds_state CHECK (state IN ('HELD', 'RELEASED', 'CONSUMED'))
) ${TABLE_OPTS}
-- @@
CREATE INDEX ix_delivery_holds_state ON delivery_holds (state, updated_at)
