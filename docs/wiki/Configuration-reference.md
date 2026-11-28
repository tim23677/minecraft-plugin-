# Configuration reference

This page lists every option of `config.yml` with its default, its allowed values and whether a restart is needed. Values outside the allowed range are replaced by the default and reported as a warning in the console. New options of later versions are added to your file automatically, existing values are never overwritten (a `.bak` copy is written before a file changes). "Needs a restart" was derived from where the plugin reads each setting while it starts, `/orderadmin reload` re-reads the rest.

| Option | Default | Allowed values | Restart needed | Meaning |
|---|---|---|---|---|
| `server-id` | `main` | a-z, 0-9, hyphen, 1 to 32 characters | yes | Unique id of this server. Every server that shares a database needs its own id. Allowed: a-z, 0-9 and hyphen, at most 32 characters. Needs a restart. |
| `language` | `en` | en, de, fr, es, pl, pt-br, ru, tr, zh-cn | no, /orderadmin reload | Language of the player texts: en, de, fr, es, pl, pt-br, ru, tr or zh-cn. The files messages_<language>.yml lie directly in plugins/OrderHub. |
| `debug` | `false` | true or false | no, /orderadmin reload | Extra console output (no confidential data). |
| `storage.type` | `sqlite` | sqlite or mariadb | yes | sqlite (one server, default) or mariadb (several servers). Needs a restart. |
| `storage.sqlite.file` | `orderhub.db` | text | yes | File name inside the plugin folder, without path parts. |
| `storage.mariadb.jdbc-url` | `jdbc:mariadb://localhost:3306/orderhub?sslMode=disable` | text | yes | Complete JDBC URL. For servers that are not local, append sslMode=verify-full or verify-ca. |
| `storage.mariadb.user` | `orderhub` | text | yes |  |
| `storage.mariadb.password-env` | `ORDERHUB_DB_PASSWORD` | text | yes | NAME of the environment variable that holds the password. The password itself is never stored in this file. |
| `storage.mariadb.pool-size` | `10` | 1 to 100 (checked at start) | yes |  |
| `storage.retry.max-attempts` | `5` | 1 to 50 | yes |  |
| `storage.retry.initial-backoff-ms` | `25` | 1 to 10000 | yes |  |
| `storage.retry.max-backoff-ms` | `500` | 1 to 60000 | yes |  |
| `orders.min-unit-price` | `0.01` | text such as 0.01, 1k, 2.5m (maximum above the minimum) | no, /orderadmin reload | Price limits per item, written as in the game (for example 0.01, 1k, 2.5m). |
| `orders.max-unit-price` | `1000000` | text such as 1000000, 1k, 2.5m | no, /orderadmin reload |  |
| `orders.max-quantity` | `2304` | 1 to 1000000 | no, /orderadmin reload | Maximum quantity per order. |
| `orders.default-max-open-orders` | `3` | 1 to 1000 | no, /orderadmin reload | Default limit of open orders. The permissions orderhub.limit.5/10/25/50 raise it, the highest limit that applies wins. |
| `orders.expiry-hours` | `168` | 1 to 24 | no, /orderadmin reload | Lifetime in hours. After that the order expires and the remaining amount is refunded. |
| `orders.cancel-word` | `cancel` | text | no, /orderadmin reload | Word that cancels a chat input. |
| `orders.input-timeout-seconds` | `60` | 5 to 600 | no, /orderadmin reload | Seconds a chat input waits. |
| `orders.create-cooldown-seconds` | `5` | 0 to 3600 | no, /orderadmin reload | Minimum seconds between two order creations of one player (0 = off). |
| `orders.query-interval-ms` | `250` | 0 to 10000 | no, /orderadmin reload | Minimum milliseconds between two lookups of one player (market, sell, info, stats, history, analytics, favorites, mine, claims, returns). Protects the database from command spam (0 = off). |
| `fees.create-fee-bp` | `100` | 0 to 10000 | no, /orderadmin reload | Fees in basis points (100 = 1 %). The permission orderhub.bypass.fee removes the creation fee. |
| `fees.sales-tax-bp` | `200` | 0 to 10000 | no, /orderadmin reload |  |
| `items.allow-custom-name` | `false` | true or false | yes | Rejected by default. Set to true to admit such items in orders. Admitting an item does not make deliveries inexact: the delivered item must still equal the ordered one in every component. |
| `items.allow-lore` | `false` | true or false | yes |  |
| `items.allow-enchanted` | `false` | true or false | yes |  |
| `items.allow-damaged` | `false` | true or false | yes |  |
| `items.component-presets` | `[ potions, arrows, fireworks, goat-horns, banners, ominous-bottles, player-heads ]` | potions, arrows, fireworks, goat-horns, banners, ominous-bottles, player-heads, all | yes | Presets for items whose special trait lies in a data component. They admit these items in orders. Delivery must still match exactly (same effect, same shape, same pattern). Available: potions          potions, splash potions, lingering potions (minecraft:potion_contents) arrows           tipped arrows (minecraft:potion_contents) fireworks        firework rockets and stars (minecraft:fireworks, firework_explosion) goat-horns       goat horns (minecraft:instrument) banners          patterned banners (minecraft:banner_patterns) ominous-bottles  ominous bottles (minecraft:ominous_bottle_amplifier) player-heads     player heads (minecraft:profile) all              all of the above Remove a preset to reject those items again. [] rejects all of them. |
| `items.allowed-components` | `[]` | list of texts | yes | More data components admitted one by one (keys like minecraft:repair_cost). Delivery is exact here as well. |
| `items.blocked-materials` | `[]` | list of texts | yes | Additionally blocked items (material keys like minecraft:elytra). |
| `items.blocked-tags` | `[]` | list of texts | yes | Additionally blocked item tags (for example minecraft:banners). |
| `delivery.click-debounce-ms` | `150` | 0 to 5000 | yes | Milliseconds of quiet between two clicks in a menu (protection against double clicks). |
| `cache.max-entries` | `5000` | 100 to 1000000 | yes | Maximum entries of the placeholder cache (PlaceholderAPI). The database is always the truth. |
| `economy.async-safe` | `false` | true or false | yes | false (default): Vault calls run on the global server thread, as EssentialsX, CMI and most economy plugins expect. true only if the provider is proven to be thread safe. |
| `economy.timeout-ms` | `3000` | 500 to 120000 | yes | Wait for a Vault answer. If the call was never started by then (for example during a lag spike), no money moved and the payment is retried with backoff. If it was started and did not finish, the payment counts as unclear (MANUAL_REVIEW). |
| `payouts.worker-interval-seconds` | `2` | 1 to 600 | yes | How often the payout queue is worked off, in seconds. Needs a restart. |
| `payouts.max-attempts` | `5` | 1 to 100 | yes | Maximum retries of payments that were safely not executed. Needs a restart. |
| `market.min-trades-for-recommendation` | `5` | 1 to 10000 | no, /orderadmin reload | Minimum number of completed trades for a price recommendation. |
| `notifications.cooldown-seconds` | `60` | 0 to 86400 | no, /orderadmin reload | Minimum seconds between two notifications of the same kind to one player. |
| `notifications.expiry-warning-hours` | `24` | 0 to 720 | no, /orderadmin reload | At login, players are told when open orders expire within this many hours (0 = off). |
| `notifications.display` | `chat` | chat, actionbar or title | no, /orderadmin reload | How the summary is shown at login: chat, actionbar or title. |
| `notifications.sound` | `(empty)` | text | no, /orderadmin reload | Sound that plays with the summary, for example entity.experience_orb.pickup. Empty = no sound. |
| `logging.transactions.console` | `false` | true or false | yes | Also write the lines to the server console. |
| `logging.transactions.file` | `true` | true or false | yes | Write the lines to logs/transactions.<n>.log. |
| `logging.transactions.file-max-mb` | `10` | 1 to 1024 | yes |  |
| `logging.transactions.file-count` | `5` | 1 to 100 | yes |  |
| `health.warn.economy-latency-ms` | `1000` | 0 to 600000 | no, /orderadmin reload |  |
| `health.warn.open-payments` | `50` | 0 to 1000000 | no, /orderadmin reload |  |
| `health.warn.oldest-payment-minutes` | `10` | 0 to 100000 | no, /orderadmin reload |  |
| `health.warn.manual-review` | `1` | 0 to 1000000 | no, /orderadmin reload |  |
| `backup.auto.enabled` | `true` | true or false | yes |  |
| `backup.auto.interval-hours` | `24` | 1 to 24 | yes |  |
| `backup.auto.keep` | `7` | 1 to 1000 | yes |  |
| `metrics.enabled` | `true` | true or false | yes | Anonymous usage statistics through bStats (version, database type, language). No player data. false turns them off for OrderHub, server wide you can turn them off in plugins/bStats/config.yml. Needs a restart. |
| `retention.history-days` | `120` | 1 to 3650 | yes | How long price history and market snapshots are kept, in days. |
| `retention.cleanup-interval-minutes` | `30` | 1 to 1440 | yes | Background cleanup, minutes between two runs. |
| `updates.check` | `false` | true or false | yes | Check for new versions against the GitHub releases of a repository. Default: off. Only the version number is requested, no data is sent. |
| `updates.repository` | `(empty)` | text | yes | Repository in the form owner/name that publishes the releases. |

Ranges for `storage.*` pool and retry settings come from the checks in the database setup, the others from the settings loader. If an option shows only "whole number", the code accepts any value it can use and bounds it where needed.

## Other files

- `menus.yml`: rows, titles, slots, materials and texts of the menus. Invalid entries are replaced by the default and reported as a warning. Applies after `/orderadmin reload`, except the delivery input slots which need a restart.
- `discord.yml`: webhook events. The webhook address belongs into the environment variable `ORDERHUB_DISCORD_WEBHOOK`.
- `messages_<language>.yml`: all player texts in MiniMessage format. A file in `plugins/OrderHub` takes precedence over the bundled one, keys you leave out fall back to English.
