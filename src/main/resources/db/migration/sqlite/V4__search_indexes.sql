-- Suche: Indizes fuer alle fuenf Sortierungen (status-gefiltert), berechnete Spalten fuer die
-- zwei Sortierungen mit Ausdruck.
ALTER TABLE orders ADD COLUMN fill_ratio REAL GENERATED ALWAYS AS (quantity_filled * 1.0 / quantity_total) VIRTUAL
-- @@
ALTER TABLE orders ADD COLUMN remaining_qty INTEGER GENERATED ALWAYS AS (quantity_total - quantity_filled) VIRTUAL
-- @@
CREATE INDEX ix_orders_s_new ON orders (status, created_at, id)
-- @@
CREATE INDEX ix_orders_s_hi ON orders (status, unit_price DESC, id)
-- @@
CREATE INDEX ix_orders_s_lo ON orders (status, unit_price, id)
-- @@
CREATE INDEX ix_orders_s_fill ON orders (status, fill_ratio DESC, id)
-- @@
CREATE INDEX ix_orders_s_dem ON orders (status, remaining_qty DESC, escrow_remaining DESC, id)
