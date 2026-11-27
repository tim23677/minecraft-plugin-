-- transactions ist append-only: UPDATE und DELETE sind verboten.
DROP TRIGGER IF EXISTS trg_transactions_no_update
-- @@
CREATE TRIGGER trg_transactions_no_update BEFORE UPDATE ON transactions
BEGIN
  SELECT RAISE(ABORT, 'transactions is append-only');
END
-- @@
DROP TRIGGER IF EXISTS trg_transactions_no_delete
-- @@
CREATE TRIGGER trg_transactions_no_delete BEFORE DELETE ON transactions
BEGIN
  SELECT RAISE(ABORT, 'transactions is append-only');
END
