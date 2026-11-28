-- OrderHub V5: Optionen je Auftrag (Toleranz, Auto-Abholung) und Auftragsvorlagen.
-- Die Optionen liegen in einer eigenen Tabelle, damit bestehende Auftraege unveraendert bleiben:
-- Ohne Zeile gilt Toleranz EXACT und keine Auto-Abholung.
-- Statements werden durch eine Zeile "-- @@" getrennt.
CREATE TABLE IF NOT EXISTS order_options (
  order_id BIGINT NOT NULL PRIMARY KEY,
  tol_durability INT NOT NULL,
  tol_ench_min SMALLINT NOT NULL,
  auto_claim SMALLINT NOT NULL,
  CONSTRAINT ck_order_options_dur CHECK (tol_durability BETWEEN 0 AND 100),
  CONSTRAINT ck_order_options_ench CHECK (tol_ench_min IN (0, 1)),
  CONSTRAINT ck_order_options_claim CHECK (auto_claim IN (0, 1)),
  CONSTRAINT fk_order_options_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ${TABLE_OPTS}
-- @@
CREATE TABLE IF NOT EXISTS order_templates (
  id ${AUTO_ID},
  owner_uuid CHAR(36) NOT NULL,
  name VARCHAR(32) NOT NULL,
  item_key VARCHAR(190) NOT NULL,
  format_version INT NOT NULL,
  item_data ${BLOB} NOT NULL,
  unit_price BIGINT NOT NULL,
  quantity INT NOT NULL,
  min_delivery INT NOT NULL,
  lifetime_hours INT NOT NULL,
  tol_durability INT NOT NULL,
  tol_ench_min SMALLINT NOT NULL,
  auto_renew SMALLINT NOT NULL,
  auto_claim SMALLINT NOT NULL,
  created_at BIGINT NOT NULL,
  CONSTRAINT uq_order_templates_name UNIQUE (owner_uuid, name),
  CONSTRAINT ck_order_templates_qty CHECK (quantity > 0 AND min_delivery >= 1),
  CONSTRAINT ck_order_templates_price CHECK (unit_price > 0),
  CONSTRAINT ck_order_templates_dur CHECK (tol_durability BETWEEN 0 AND 100)
) ${TABLE_OPTS}
