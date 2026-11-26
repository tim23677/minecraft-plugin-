# Configuration

Files live in `plugins/OrderHub`. New options of later versions are added automatically with their default value, existing values are never overwritten (a `.bak` copy is written before a file changes). Changes to texts, cancel word and timeouts apply with `/orderadmin reload`. Fees, price and quantity limits and the default order lifetime apply with `/orderadmin reload` as one atomic snapshot. Database settings, payout worker settings and the Discord worker need a restart.

## config.yml

| Option | Default | Meaning |
|---|---|---|
| server-id | main | unique per server sharing a database (a-z, 0-9, hyphen, up to 32) |
| language | en | `en` or `de` |
| debug | false | extra console output |
| orders.min-unit-price / max-unit-price | 0.01 / 1000000 | price per item, `k`, `m`, `b` allowed |
| orders.max-quantity | 2304 | items per order |
| orders.default-max-open-orders | 3 | raised by `orderhub.limit.N` |
| orders.expiry-hours | 168 | lifetime of an order |
| orders.cancel-word, input-timeout-seconds | cancel, 60 | chat input |
| fees.create-fee-bp, sales-tax-bp | 100, 200 | basis points (100 = 1 percent) |
| items.allow-custom-name / lore / enchanted / damaged | false | relax the default item rejection |
| items.blocked-materials, blocked-tags | empty | extra blocked items (`minecraft:elytra`, tags) |
| delivery.click-debounce-ms | 150 | minimum time between menu clicks |
| cache.max-entries | 5000 | size of the PlaceholderAPI value cache (the database is always the truth, there is no cross-server cache) |
| economy.async-safe, timeout-ms | false, 10000 | false: all Vault calls run on the global server thread and wait up to timeout-ms; a timeout on withdraw/deposit counts as unknown outcome (MANUAL_REVIEW), never retried |
| payouts.worker-interval-seconds, max-attempts | 2, 5 | payout worker |
| market.min-trades-for-recommendation | 5 | price recommendation needs real trades |
| notifications.cooldown-seconds | 60 | price alert spam protection |
| retention.history-days, cleanup-interval-minutes | 120, 30 | trade history cleanup |

Options that existed in earlier builds without any effect (`reputation.enabled`, `sounds.*`, `commands.aliases`, `cache.sync-interval-seconds`, `market.recommendation-window-days`, `notifications.batch-window-seconds`) were removed. If an old `config.yml` still contains them, or any misspelled key, OrderHub logs `unknown or unused option '<key>' is ignored` at start.

## storage (in config.yml)

`storage.type: sqlite` (default) or `mariadb`. SQLite: `storage.sqlite.file` is a plain file name in the plugin folder. MariaDB: `storage.mariadb.jdbc-url` (full JDBC URL, add `sslMode=verify-full` for non local servers), `user`, `pool-size` and `password-env`, the NAME of the environment variable that holds the password. `storage.retry` controls deadlock retries. The database is opened asynchronously during start. If it cannot be opened, OrderHub logs the reason and disables itself. Changes need a restart.

## discord.yml

`enabled`, `webhook-url` (prefer `ORDERHUB_DISCORD_WEBHOOK`), `username`, `events.*`, `large-order-threshold`, `delivery.*` (attempts and backoff). Only `https://discord.com/api/webhooks/...` style URLs are accepted.

## menus.yml

`delivery.input-slots` (slot numbers 0 to 44) is used. The other menu options are reserved for a later GUI customization pass.

## messages_en.yml, messages_de.yml

All player texts, MiniMessage format. Copy a file to add a language and set `language` to its name. Missing keys fall back to English.
