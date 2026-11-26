-- transactions ist append-only: UPDATE und DELETE sind verboten.
-- Hinweis: Mit aktiviertem Binlog braucht der DB-Benutzer SUPER bzw. log_bin_trust_function_creators=1
-- oder das Recht TRIGGER (je nach Server-Version).
DROP TRIGGER IF EXISTS trg_transactions_no_update
-- @@
CREATE TRIGGER trg_transactions_no_update BEFORE UPDATE ON transactions
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions is append-only'
-- @@
DROP TRIGGER IF EXISTS trg_transactions_no_delete
-- @@
CREATE TRIGGER trg_transactions_no_delete BEFORE DELETE ON transactions
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions is append-only'
