# Backup and restore

SQLite: `/orderadmin backup confirm <reason>`. It writes a consistent copy (`VACUUM INTO`) to `plugins/OrderHub/backups`. Scheduled backups are on by default (`backup.auto`, every 24 hours, 7 kept). A backup is also made before every schema migration of an existing database.

Restore: stop the server, replace `plugins/OrderHub/orderhub.db` by the backup file, delete `orderhub.db-wal` and `orderhub.db-shm` if present, start the server. Run `/orderadmin recovery confirm <reason>` if the backup was taken while payments were running.

MariaDB: OrderHub does not back up MariaDB. Use `mysqldump --single-transaction orderhub > orderhub.sql` or a snapshot of the database server. Restore with `mysql orderhub < orderhub.sql` while all servers are stopped.

CSV export for review: `/orderadmin export orders|ledger confirm <reason>` writes to `plugins/OrderHub/exports`.
