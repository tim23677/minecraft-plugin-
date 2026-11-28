# OrderHub

OrderHub is a buy order marketplace for Paper and Folia servers. A player creates an order ("I want 64 diamonds for 8 each"), the money is held in escrow, other players deliver the items and are paid immediately, and the buyer collects the items later.

This README is written for server owners. No Java knowledge is needed.

## Status of this version

OrderHub 2.0.0 is built with automated unit, MockBukkit, SQLite and MariaDB 11.4 tests (Testcontainers). The checks that were run for this release, and those that were not, are listed with their commands in RELEASE_REPORT.md. COMPATIBILITY.md lists what was tested on which platform. Real server runs of 2.0.0 (Paper, Folia, bots) were not repeated for this version, run OrderHub on a test server with your own economy plugin before you use it on a live server.

## Requirements

- Paper or Folia 1.21.x (built against Paper API 1.21.11), Java 21
- Vault and an economy plugin that registers with Vault (required to create orders and to pay out)
- Internet access to Maven Central on the first start: Paper downloads the database libraries (HikariCP, SQLite and MariaDB drivers) once into its `libraries` folder. The plugin jar itself is small. The sales package also contains a second jar `OrderHub-<version>-bundled.jar` with HikariCP and SQLite included for servers without internet access (SQLite only, MariaDB still needs the download because its driver is LGPL licensed, see THIRD_PARTY_NOTICES.md)
- Optional: PlaceholderAPI
- Optional: MariaDB 11.4 for several servers sharing one marketplace (SQLite is the default for a single server). MySQL is not supported.

## Installation

1. Stop the server.
2. Put `OrderHub-<version>.jar` into the `plugins` folder.
3. Install Vault and an economy plugin if you do not have them yet.
4. Start the server once. OrderHub creates `plugins/OrderHub` with `config.yml`, `discord.yml`, `menus.yml` and `menus.yml` and the language files `messages_<language>.yml`.
5. Adjust prices, fees and limits in `config.yml`, set `language` (en, de, fr, es, pl, pt-br, ru, tr, zh-cn), then run `/orderadmin reload` (database and payout worker settings need a restart).

## Commands

Players:

- `/order` opens the main menu
- `/order create [quantity] [price] [options]` creates an order for the item in your hand (prices accept `k`, `m`, `b`, for example `1.5k`). Options: `durability=<0-100>` accepts damaged items with at least that much durability left, `enchants` accepts items with at least the enchantments of your item, `autoclaim` hands delivered items to you at once while you are online
- `/order mine` lists your orders (left click cancels, right click pauses or resumes)
- `/order market [newest|highest-price|lowest-price|almost-completed|highest-demand] [search text] [min=<price>] [max=<price>]` opens the marketplace (prices accept `k`, `m`, `b`). The menu has a search by item name, categories and sorting, and pages through large markets with keyset pagination
- `/order sell [amount|all]` finds the open order with the best price for the item in your hand and opens the delivery menu with the items as a preview. Nothing is delivered before you confirm
- `/order template [save <name> <quantity> <price> [options]|list|use <name>|delete <name>]` stores and reuses order settings (at most 10 per player). Without arguments it opens a menu: left click uses a template, right click deletes it
- `/order info <id>` shows quantity, price, status, remaining time and options of an order
- `/order renew <id>` switches automatic renewal of one of your orders on or off
- `/order deliver <id>` opens the delivery menu for an order
- `/order claims` collects items delivered to your orders, `/order returns` collects items that could not be handed back earlier
- `/order cancel <id>` cancels one of your orders
- `/order stats`, `/order history`, `/order analytics [item]`
- `/order favorites [add|remove|alert <price>|alert-off]` works with the item in your hand

Administrators (permission `orderhub.admin`):

- `/orderadmin list [status]`, `statistics`, `transactions [player-uuid]`, `transaction <uuid>`, `inspect <order-id>`
- `/orderadmin cancel <id> confirm <reason>`
- `/orderadmin review list`, `review show <tx>`, `review resolve <tx> paid|refund|discard confirm <reason>` resolve payments that ended in MANUAL_REVIEW
- `/orderadmin purge-expired confirm <reason>`
- `/orderadmin recovery confirm <reason>`
- `/orderadmin player <name|uuid>` shows open orders, waiting parcels and returns and the statistics of one player
- `/orderadmin audit [n]` lists the last audit entries (default 10, at most 50)
- `/orderadmin health` shows version, platform, Java, uptime, database pool, queue, open payments, manual review count and the last recovery
- `/orderadmin backup confirm <reason>` writes a consistent SQLite snapshot to `plugins/OrderHub/backups` (MariaDB: use mysqldump or a server snapshot)
- `/orderadmin export orders|ledger confirm <reason>` writes a CSV file to `plugins/OrderHub/exports`
- `/orderadmin reload`, `/orderadmin debug`

Every changing admin command needs `confirm` and a reason and is written to the audit log.

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| orderhub.use | everyone | use OrderHub at all |
| orderhub.create | everyone | create and manage own orders |
| orderhub.market | everyone | marketplace, delivery, analytics |
| orderhub.stats | everyone | statistics and history |
| orderhub.favorites | everyone | favorites and price alerts |
| orderhub.bypass.fee | nobody | no creation fee |
| orderhub.limit.5, .10, .25, .50 | nobody | open order limit, the highest one a player has applies |
| orderhub.admin | operators | all admin commands |
| orderhub.admin.reload, .transactions, .recovery, .backup, .view, .cancel | operators | single admin areas (`.backup` covers backup and CSV export) |

## Vault

OrderHub talks to your economy only through Vault. If Vault or the economy plugin is missing, the plugin still starts, but creating orders and payouts are refused until it is available. Payouts that fail because of a provider problem are retried; payouts with an unclear result (for example a crash during payout) are never repeated automatically and appear as MANUAL_REVIEW in `/orderadmin statistics`.

## PlaceholderAPI

Available placeholders: `%orderhub_open_orders%`, `%orderhub_completed_orders%`, `%orderhub_total_volume%`, `%orderhub_player_earnings%`, `%orderhub_player_deliveries%`, `%orderhub_market_average_<item>%`, `%orderhub_market_demand_<item>%`, `%orderhub_market_open_orders%`, `%orderhub_market_highest_<item>%`, `%orderhub_market_lowest_<item>%`, `%orderhub_claims_waiting%` (for example `%orderhub_market_average_diamond%`). Values come from a short-lived cache, the first request after a restart shows a fallback (`0` or `-`) until the value has been loaded.

## Databases

Single server: nothing to do, SQLite is used (`plugins/OrderHub/orderhub.db`).

Several servers: set `storage.type: mariadb` in `config.yml`, fill in `storage.mariadb.jdbc-url` and `user`, create an empty database and a user for it, and give every server its own `server-id`. The password is never stored in the file: `storage.mariadb.password-env` names the environment variable that holds it (default `ORDERHUB_DB_PASSWORD`). Tested with MariaDB 11.4. MySQL is not supported (the migrations use `CREATE INDEX IF NOT EXISTS`, which MySQL 8 does not have).

## Discord

Set `enabled: true` in `discord.yml`, put a webhook URL in the environment variable `ORDERHUB_DISCORD_WEBHOOK` (or `webhook-url` in the file) and choose the events. Messages contain order numbers, item names, quantities and amounts, never player names or UUIDs.

## Frequently asked questions

Can money or items be lost during a crash? Every money or item movement is written to a journal first. After a restart OrderHub finishes safe cases itself and moves unclear ones to MANUAL_REVIEW instead of guessing. It cannot guarantee that a crash in the exact moment between your economy plugin paying and OrderHub saving the result is detected as paid, which is why that case is escalated to an administrator.

Why do I see "Not enough market data"? A price recommendation needs a minimum number of completed trades (default 5 in 7 days). OrderHub does not estimate prices without data.

## Troubleshooting

- The plugin disables itself on start: read the console. A broken `storage` section or an unreachable database is reported there.
- "Vault is not installed" or no economy provider: install both, restart.
- Items are not accepted in the delivery menu: the item must match the order exactly (name, enchantments, lore and damage must match, containers are rejected).
- A player says an item is missing: check `/order claims` and `/order returns`, then `/orderadmin transactions <player-uuid>`.
