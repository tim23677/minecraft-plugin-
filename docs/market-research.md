# Market research: buy-order plugins

Read on 2026-11-28 from the public listing pages linked below. The notes cover only what these pages state. Nothing was installed or tested, so nothing here says how well another plugin works.

## Plugins found

| Plugin | Listing | What the page states |
|---|---|---|
| ApxOrder 1.0.0 | https://www.spigotmc.org/resources/apxorder.140808/ | Paper 1.21+, Java 21, Vault required, PlaceholderAPI optional. SQLite and MySQL/MariaDB with HikariCP. Escrow through Vault, unclaimed item storage, search, sorting and category filter, customizable menus, order statistics. Commands `/order`, `/order create <item> <amount> <price>`, `/orderadmin` with `orders`, `info`, `cancel`, `reload`. |
| Donut Orders 1.9 | https://www.spigotmc.org/resources/donut-orders.129913/ | Public buy orders, three-step delivery menu with a confirmation that shows the payout, a private 54-slot stash for delivered items, chat input for amount and price, number formatting (1K, 1M, 21.5B), order expiry with countdown, configurable titles and messages, Vault. Reviews on the page ask for configurable menus and sounds, and for `k`, `m`, `b` input. |
| Orders (Modrinth) | https://modrinth.com/plugin/orders-gurbaksh | Search result text: buy orders backed by money up front, instant payout on delivery, command `/orders`. |
| Calmo's Order (Modrinth) | https://modrinth.com/plugin/calmos-order | Search result text: players create buy orders for items they need, others fulfil them. The page itself did not load more than the title. |
| YOrder, FoOrders (BuiltByBit) | https://builtbybit.com/tags/orders/ and https://builtbybit.com/resources/foorders.106170/ | Search result text only: player-driven order marketplace, order area that shows all orders, create orders for any item. |

## What the listings do not mention

The pages above do not mention any of these, so they are the points where OrderHub differs on paper. This is a statement about the listing texts, not about the plugins:

- a transaction journal with idempotency keys and recovery after a crash between payment and item hand-over
- a timeout on Vault calls and a rule that an unclear payment outcome is never retried
- Folia support
- several servers on one database with keyset pagination
- order options such as minimum durability and "enchantments at least"
- a public developer API with events

## Consequences for OrderHub

- Chat input and a confirmation step are common, OrderHub has both.
- Users ask for `k`, `m`, `b` in prices (Donut Orders reviews), OrderHub accepts them.
- Users ask for configurable menus, texts and sounds (same reviews), OrderHub has `menus.yml`, nine language files and `notifications.sound`.
- A stash or parcel storage for delivered items is common, OrderHub has claims and returns, plus auto-claim.

## Not done

Importing orders from these plugins needs their storage format. None of the pages describes it, so there is no import. Doing it needs the plugin jars and their database files.
