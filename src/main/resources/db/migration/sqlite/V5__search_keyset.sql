-- Keyset-Paginierung: ganzzahliger Fuellstand in Promille (die Gleitkommaspalte fill_ratio aus V4
-- taugt nicht als Cursor) und ein Index fuer Kategoriefilter.
ALTER TABLE orders ADD COLUMN fill_permille INTEGER GENERATED ALWAYS AS ((quantity_filled * 1000) / quantity_total) VIRTUAL
-- @@
CREATE INDEX ix_orders_s_fillp ON orders (status, fill_permille DESC, id)
-- @@
CREATE INDEX ix_orders_s_cat ON orders (status, category, created_at DESC, id DESC)
