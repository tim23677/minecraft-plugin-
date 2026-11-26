# OrderHub

OrderHub is a buy order marketplace for Paper and Folia servers. A player creates an order ("I want 64 diamonds for 8 each"), the money is held in escrow, other players deliver the items and are paid immediately, and the buyer collects the items later.

This README is written for server owners. No Java knowledge is needed.

## Status of this build

OrderHub 0.1.0 is a development build. The core flow (create, deliver, pay out, collect, cancel, expire, recover after a crash) is implemented and tested with automated tests against SQLite and MariaDB. It has not yet been run on a real Paper or Folia server by the author, so run it on a test server first. RELEASE_REPORT.md lists exactly what was tested, what was not, and which features from the specification are missing.

## Requirements

- Paper or Folia 1.21.x (built against Paper API 1.21.11), Java 21
- Vault and an economy plugin that registers with Vault (required to create orders and to pay out)
- Optional: PlaceholderAPI
- Optional: MariaDB or MySQL for several servers sharing one marketplace (SQLite is the default for a single server)

## Installation

1. Stop the server.
2. Put `OrderHub-<version>.jar` into the `plugins` folder.
3. Install Vault and an economy plugin if you do not have them yet.
4. Start the server once. OrderHub creates `plugins/OrderHub` with `config.yml`, `discord.yml`, `menus.yml` and `messages_en.yml`, `messages_de.yml`.
5. Adjust prices, fees and limits in `config.yml`, set `language: "de"` for German, then run `/orderadmin reload` (fees, limits and database settings need a restart).

## Commands

Players:

- `/order` opens the main menu
- `/order create [quantity] [price]` creates an order for the item in your hand (prices accept `k`, `m`, `b`, for example `1.5k`)
- `/order mine` lists your orders (left click cancels, right click pauses or resumes)
- `/order market [newest|highest-price|lowest-price|almost-completed|highest-demand]` opens the marketplace
- `/order deliver <id>` opens the delivery menu for an order
- `/order claims` collects items delivered to your orders, `/order returns` collects items that could not be handed back earlier
- `/order cancel <id>` cancels one of your orders
- `/order stats`, `/order history`, `/order analytics [item]`
- `/order favorites [add|remove|alert <price>|alert-off]` works with the item in your hand

Administrators (permission `orderhub.admin`):

- `/orderadmin list [status]`, `statistics`, `transactions [player-uuid]`, `transaction <uuid>`, `inspect <order-id>`
- `/orderadmin cancel <id> confirm <reason>`
- `/orderadmin purge-expired confirm <reason>`
- `/orderadmin recovery confirm <reason>`
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
| orderhub.admin.reload, .transactions, .recovery | operators | single admin areas |

## Vault

OrderHub talks to your economy only through Vault. If Vault or the economy plugin is missing, the plugin still starts, but creating orders and payouts are refused until it is available. Payouts that fail because of a provider problem are retried; payouts with an unclear result (for example a crash during payout) are never repeated automatically and appear as MANUAL_REVIEW in `/orderadmin statistics`.

## PlaceholderAPI

Available placeholders: `%orderhub_open_orders%`, `%orderhub_completed_orders%`, `%orderhub_total_volume%`, `%orderhub_player_earnings%`, `%orderhub_player_deliveries%`, `%orderhub_market_average_<item>%`, `%orderhub_market_demand_<item>%` (for example `%orderhub_market_average_diamond%`). Values come from a short-lived cache, the first request after a restart shows a fallback (`0` or `-`) until the value has been loaded.

## Databases

Single server: nothing to do, SQLite is used (`plugins/OrderHub/orderhub.db`).

Several servers: set `storage.type: mariadb` in `config.yml`, fill in `storage.mariadb.jdbc-url` and `user`, create an empty database and a user for it, and give every server its own `server-id`. The password is never stored in the file: `storage.mariadb.password-env` names the environment variable that holds it (default `ORDERHUB_DB_PASSWORD`). Tested with MariaDB 10.11. MySQL has not been tested.

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
