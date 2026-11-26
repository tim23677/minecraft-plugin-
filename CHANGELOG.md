# Changelog

## 0.1.0 (development build)

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

Known gaps are listed in RELEASE_REPORT.md.
