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

Known gaps are listed in RELEASE_REPORT.md.
