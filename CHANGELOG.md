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

Known gaps are listed in RELEASE_REPORT.md.
