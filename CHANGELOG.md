# Changelog

## 2.0.0

Added

- Order options reachable from commands: `/order create <quantity> <price> [durability=<0-100>] [enchants] [autoclaim]`, the same flags for `/order template save`, shown in the order summary and in `/order info <id>`
- Auto-claim: with `autoclaim` set, delivered items go straight into the owner's inventory while online, what does not fit stays a parcel
- `/order template` without arguments opens a menu (left click uses, right click deletes)
- `/order sell all` offers every matching item of the inventory to the best order
- `/order info <id>`
- Suggestion for mistyped subcommands, complete `/order help`, tab completion for template, sell and option flags
- Placeholders `%orderhub_market_open_orders%` and `%orderhub_claims_waiting%`
- Invalid `notifications.sound` values produce a warning and turn the sound off
- Marketplace menu with search by item name, categories, sorting and keyset pagination (stable pages while orders change), layout and texts in `menus.yml`
- `/order sell [amount]`: finds the open order with the best price for the item in hand and opens the delivery menu as a preview, delivery happens only after confirming
- Per order options stored in the database: minimum remaining durability and "enchantments at least" tolerance for deliveries and an auto-claim flag
- Component presets in `items.component-presets` (potions, arrows, fireworks, goat horns, banners, ominous bottles, player heads): these items can be ordered, delivery still has to match every component exactly
- `/order template` (save, list, use, delete; 10 per player) and `/order renew <id>` (automatic renewal on or off)
- Menus for statistics, history, favorites and market analytics
- Login summary of delivered items and expired orders, shown as chat, actionbar or title, optional sound (`notifications.*`); cross-server notes through the shared database
- Seven new languages: French, Spanish, Polish, Brazilian Portuguese, Russian, Turkish, Simplified Chinese (a test checks that every language file has all keys and placeholders)
- Structured transaction log file with rotation (`logging.transactions.*`)
- Economy latency measurement, extended `/orderadmin health` with warnings (`health.warn.*`)
- Scheduled SQLite backups (`backup.auto.*`)
- bStats (`metrics.enabled`), inactive until a plugin id is registered, can be turned off
- `/orderadmin dump`: support bundle without secrets (new permission `orderhub.admin.dump`)
- Developer API as its own module `orderhub-api` with Javadoc and `@since`, input validation, an example plugin in `examples/order-listener`, binary compatibility check against 1.1.0 (japicmp)
- Sales package with `SHA256SUMS`, SBOM (CycloneDX), reproducible jars, and a second jar with HikariCP and SQLite included
- Database migration V5 for SQLite and MariaDB (order options, templates table, keyset column), upgrade test with a real 1.1.0 database

Changed

- `/order sell` loads the options of all candidate orders in one query instead of one query per order
- Text searches in the marketplace are limited to 3 per 5 seconds and player
- `/orderadmin dump` reads only the last 2 MB of the server log
- Login notes that were already claimed for a player who left in the same moment are released and shown at the next login
- The `open_orders` placeholder counts without row locks, it can no longer delay order creation on MariaDB
- Vault calls have a timeout (`economy.timeout-ms`, default 3000). A call that was never started during a lag spike is retried later, a call that was started and did not finish counts as unclear (MANUAL_REVIEW) and is never repeated automatically
- `config.yml` is written in English, `config-version` 3 (the updater adds new options and keeps your values)
- Items with their own data components (potions, banners and so on) are accepted by default through the presets, they were rejected before
- Create cooldown for orders (`orders.create-cooldown-seconds`)
- GitHub Actions are pinned to commit SHAs, the Gradle wrapper is the official one with a checksum

Fixed

- A delivery of an item that differs from the order only in a tolerated trait now matches according to the order options instead of being rejected without a reason
- Duplicate key `admin.transaction` in the German message file, missing German admin texts

Not included in 2.0.0 (see RELEASE_REPORT.md): create menu with all options, auto-renew fee, review menu in the GUI, import from other plugins, offline MariaDB driver.

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
