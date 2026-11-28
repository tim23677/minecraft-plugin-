-- Beanspruchung einer Discord-Nachricht vor dem Senden (mehrere Server, eine Datenbank).
-- claimed_until ist ein Zeitlimit: ein abgestuerzter Server blockiert die Zeile nicht ewig.
ALTER TABLE discord_outbox ADD COLUMN claimed_by VARCHAR(80) NULL
-- @@
ALTER TABLE discord_outbox ADD COLUMN claimed_until BIGINT NULL
