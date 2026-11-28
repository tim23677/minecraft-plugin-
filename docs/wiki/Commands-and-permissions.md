# Commands and permissions

Player commands: `/order`, `/order create [quantity] [price] [durability=<0-100>] [enchants] [autoclaim]`, `/order mine`, `/order market [sort] [search text] [min=<price>] [max=<price>]`, `/order sell [amount|all]`, `/order template [save|list|use|delete]` (without arguments: menu), `/order info <id>`, `/order renew <id>`, `/order deliver <id>`, `/order claims`, `/order returns`, `/order cancel <id>`, `/order stats`, `/order history`, `/order analytics [item]`, `/order favorites [add|remove|alert <price>|alert-off]`, `/order help`.

Admin commands (all changing commands need `confirm` and a reason, and are written to the audit log): `/orderadmin list [status]`, `cancel <id> confirm <reason>`, `purge-expired confirm <reason>`, `transactions [player-uuid]`, `transaction <uuid>`, `inspect <order-id>`, `review list|show|resolve`, `health`, `dump`, `backup confirm <reason>`, `export orders|ledger confirm <reason>`, `recovery confirm <reason>`, `statistics`, `player <name|uuid>`, `audit [n]`, `reload`, `debug`.

| Permission | Default |
|---|---|
| orderhub.use, orderhub.create, orderhub.market, orderhub.stats, orderhub.favorites | everyone |
| orderhub.bypass.fee | nobody |
| orderhub.limit.5, .10, .25, .50 | nobody (highest applies) |
| orderhub.admin | operators, includes all below |
| orderhub.admin.reload, .transactions, .recovery, .backup, .dump, .view, .cancel | operators |
