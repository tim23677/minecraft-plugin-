# Changelog

## 1.1.0 (2026-11-26)

Added

- `/orderadmin health`: database pool, executor queue, open and running payments, manual review count, last recovery
- `/orderadmin backup confirm <reason>`: consistent SQLite snapshot (`VACUUM INTO`) in `plugins/OrderHub/backups`, keeps the newest 10
- `/orderadmin export orders|ledger confirm <reason>`: CSV in `plugins/OrderHub/exports`, cells that start with `= + - @` are protected against spreadsheet formulas. New permission `orderhub.admin.backup`
- Automatic SQLite backup before a schema migration on an existing database (`backups/pre-migration-v*.db`, newest 5 kept); migration is refused if the backup fails
- `config-version` 2 with a migration path: version 1 files lose the options that never had an effect, the original is kept as `config.yml.bak`, user values and comments stay, a file from a newer plugin is left untouched with a warning
- Optional update check against GitHub releases (`updates.check`, off by default, only the version number is requested)
- Tag-triggered release workflow with provenance attestation and `tools/release/package.sh`

Changed

- Surplus items of a delivery are shown through a persistent return that the player picks up right away (see 1.0.0 return reservation)
- Item names in messages fall back to the material name if the runtime cannot provide an effective name

Tests

- MockBukkit tests for `DeliveryFlow` (10), `CreateFlow` (9) and `ParcelFlow` (4): normal case, cancel, close, disconnect, double click, full inventory, database outage, lost commit acknowledgement, economy failure
- Player scenario on a real Paper 1.21.11 server with VaultUnlocked and EssentialsX played by two bots, including a hard kill in the middle of a delivery (`tools/e2e/e2e.js`, 17 of 17 checks)

## 1.0.0 (2026-11-26)

Added

- Buy orders with escrow, creation fee, sales tax and expiry
- Delivery menu with server side item checks, partial and full deliveries
- Persistent claims and return queue, no item drops
- Transaction journal with idempotency keys, crash recovery and manual review state
- SQLite and MariaDB storage with versioned migrations
- Marketplace, my orders, claims menus, statistics, history, favorites, price alerts, market analytics (chat based)
- Discord webhook outbox with backoff, PlaceholderAPI expansion, public developer API with events
- Admin commands with confirmation, reason and audit log
- English and German language files

- Vault calls run on the global server thread by default (`economy.async-safe: false`), with timeout treated as unknown outcome

- `/orderadmin review list|show|resolve <tx> paid|refund|discard confirm <reason>` resolves MANUAL_REVIEW entries through the journal, idempotent, audited in the same transaction

- The create confirmation stores the displayed fee; the engine aborts with `FEE_CHANGED` before any charge when it differs, and the display now uses the same fee source as the engine

- Delivery return reservation (`delivery_holds`, migration V2): a delivery persists all items in the menu together with PREPARED. A crash or disable before ITEMS_REMOVED, after it, or after commit never loses or duplicates items; the surplus beyond the delivered quantity always becomes a persistent return

- API `createOrder` checks `orderhub.create`, the owner's `orderhub.limit.*` tier and `orderhub.bypass.fee` like the command, rejects offline owners unless `CreateOptions(bypassChecks=true)` is passed, and writes the calling plugin to the audit log (`api.order.create`). New add-only statuses `NO_PERMISSION` and `FEE_CHANGED`
- A delivery whose prepare outcome is unknown is closed atomically with a tombstone before items go back directly; a recovery that was faster than the flow never leads to returning items twice

- Audit entries for `cancel`, `purge-expired` and `review resolve` are written in the same transaction as the action, `reload` is audited. `recovery` writes one summary entry after the sweep (a sweep spans many transactions; each changed journal entry carries its own detail)

- Discord outbox rows are claimed with a conditional UPDATE (`claimed_by`, `claimed_until`, migration V3) before sending, so several servers on one database never send the same message twice; stale claims of crashed servers expire after 60 s

- Fees, limits and expiry are one immutable snapshot (`Rates`) that `/orderadmin reload` swaps atomically; changed fees apply without a restart and the amount shown at confirmation is checked against the charged amount

- Jar size 13.7 MB to 0.5 MB: Hikari, SQLite and MariaDB drivers load through the Paper library loader (`libraries` in plugin.yml, Maven Central) instead of being bundled; the plugin uses Paper's own slf4j, so no SLF4J error lines appear at startup

- MySQL is not supported: the plugin refuses to start against a MySQL server with a clear message (use MariaDB 10.6+ or SQLite)

Known gaps are listed in RELEASE_REPORT.md.
