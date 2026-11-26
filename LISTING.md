# BuiltByBit listing (English)

Listing text for the resource page. Replace the status line and the version before publishing and only keep claims that are true for the released version.

## Title

OrderHub | Player Buy Order Marketplace with Escrow | Paper and Folia 1.21 | SQLite and MariaDB

## Short description

Players post buy orders for items, other players deliver and get paid immediately, and every money and item movement is written to a recoverable journal.

## Description

OrderHub gives your server a player to player buy order market. A player holds an item, sets a quantity and a price per item, and OrderHub holds the full amount in escrow. Other players open the delivery menu, put matching items in, and are paid right away. The buyer collects the delivered items from a claim menu. Nothing is dropped on the ground: whatever does not fit into an inventory stays stored until there is room.

### What it does

- Buy orders with escrow, creation fee, sales tax, expiry and refunds of the remaining amount
- Partial and full deliveries, minimum delivery size per order in the engine
- Server side item checks: named, enchanted, damaged, lore items and containers are rejected by default, shulker boxes, bundles, spawn eggs, command blocks, bedrock and barrier are blocked, everything is configurable
- Delivery, claim and return menus built on a hardened inventory framework that cancels shift clicks, number keys, offhand swaps, double clicks, drags and drops
- Transaction journal with idempotency keys and a state machine, crash recovery at startup, a MANUAL_REVIEW state for cases that cannot be decided safely
- Marketplace and my orders menus, statistics, trade history, favorites with price alerts, market analytics from real completed trades (median, average, volume, price range once enough trades exist)
- SQLite for one server, MariaDB for several servers sharing one marketplace (row locks and version checks)
- Discord webhook notifications with retry and rate limit handling, never containing player names
- PlaceholderAPI placeholders, developer API with six services and six events
- English and German language files, MiniMessage formatting, all texts editable
- Folia ready: all tasks run through region, entity, global or async schedulers

### Honest limits

- Vault has no transaction ids. If the server crashes in the exact moment between the economy plugin paying and OrderHub saving that fact, OrderHub cannot know whether the payment happened. It does not pay twice automatically. It marks the case MANUAL_REVIEW for an administrator.
- No software can promise zero dupes. OrderHub is designed so that item and money movements are journaled, checked and idempotent, and it ships with automated tests for these paths, but you should still test it on your server setup first.
- Statistics, history, favorites, analytics and the admin tools are chat based in this version (no inventory menus yet).
- Not included in this version: order templates, bulk order creation, reputation system, per group or per world orders, configurable sounds.
- Tested with MariaDB 10.11. MySQL is not tested.

(Remove or update any line above when the released version changes.)

## Compatible versions

Paper and Folia 1.21.x, Java 21. Not for Spigot or Bukkit.

## Dependencies

Required: Vault and an economy plugin registered with Vault. Optional: PlaceholderAPI, MariaDB.

## Installation

1. Put the jar into `plugins`.
2. Make sure Vault and an economy plugin are installed.
3. Start the server, edit `plugins/OrderHub/config.yml` and `messages_en.yml` or `messages_de.yml`.
4. Restart. For several servers set `storage.type: mariadb` in `config.yml` and a unique `server-id` per server.

## Configuration

`config.yml` (storage, limits, fees, expiry, item rules), `discord.yml`, `menus.yml`, `messages_en.yml`, `messages_de.yml`. Every option is documented in CONFIGURATION.md.

## Support

Use the resource discussion on BuiltByBit. Include the console output of the first start and, for item or money questions, the result of `/orderadmin transactions <player-uuid>`.

## FAQ

Does it work without Vault? The plugin starts, but orders and payouts are refused until Vault and an economy plugin are present.

Can I run it on a network? Yes, with MariaDB and a different `server-id` on every server.

Does it send data anywhere? No telemetry and no license check. The only outgoing connection is the optional Discord webhook.

## AI disclosure

This plugin was developed with the help of AI coding assistants and reviewed and tested with automated tools. State this according to the BuiltByBit rules in force on the day you publish.

## Changelog

See CHANGELOG.md.

## Screenshot plan

Take every screenshot from the finished plugin on a real server, no mock-ups:

1. Main menu (`/order`)
2. Marketplace with several orders (`/order market`)
3. Order creation: chat prompt and confirm menu (`/order create`)
4. Delivery menu with items inside and the preview in the info icon
5. My orders (`/order mine`)
6. Market analytics output (`/order analytics diamond`) after enough trades exist
7. Player statistics (`/order stats`)
8. Admin output (`/orderadmin statistics`, `/orderadmin list`)
9. Transaction inspector output (`/orderadmin transaction <uuid>`)
10. A Discord channel with a large order message
