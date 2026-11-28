# Troubleshooting

| Symptom | Cause and action |
|---|---|
| Plugin disables itself at start | Read the console. A broken `storage` section, an unreachable database or MySQL instead of MariaDB is reported there. |
| No economy, orders cannot be created | Vault or the economy plugin is missing. Install both and restart. |
| "MANUAL_REVIEW" payments | The economy call was started and did not finish in time (`economy.timeout-ms`), so OrderHub cannot know whether money moved. Check the player balance and resolve with `/orderadmin review resolve <tx> paid|refund|discard confirm <reason>`. |
| Health warning "economy latency" | The economy plugin answers slowly. Raise `health.warn.economy-latency-ms` only if that is normal for your setup. |
| Item is not accepted in the delivery menu | The item must match the order in every component. Check the order options and `items.*`. |
| Player says an item is missing | `/order claims`, `/order returns`, then `/orderadmin transactions <player-uuid>`. |
| Log file | `plugins/OrderHub/logs/transactions.<n>.log` has one line per money or item movement. |
| Support | Run `/orderadmin dump` and attach the file. It contains no passwords. |
