# Screenshots and GIFs for the listing

These images are not part of the repository. They have to be recorded on a test server, because the plugin only shows menus to a real client.

## Test server

1. Paper 1.21.11, offline mode for two test accounts, Vault and EssentialsX.
2. `plugins/OrderHub-2.0.0.jar`, English language, default `menus.yml`.
3. Give both accounts money (`/eco give <name> 100000`) and a stack of diamonds, iron ingots and one enchanted sword.
4. Resolution 1920x1080, GUI scale 3, no other plugin chat.

## Screenshots

| File | Content | Steps |
|---|---|---|
| market.png | Marketplace with about 30 orders | Create orders for several items with account A, open `/order market` with account B |
| market-search.png | Search and category button | Click the compass and the sign in the bottom row |
| create-confirm.png | Summary and confirm menu | Hold diamonds, `/order create 64 12.5 durability=60 autoclaim` |
| delivery.png | Delivery menu with items | Account B opens an order, puts diamonds into the slots |
| claims.png | Collect menu | Account A, `/order claims` |
| stats.png | Statistics menu | `/order stats` after a few trades |
| analytics.png | Market analytics | `/order analytics diamond` |
| templates.png | Template menu | `/order template save ores 64 10`, then `/order template` |
| info.png | Chat output | `/order info <id>` |

## GIFs (10 to 20 seconds)

1. Create an order and watch the money leave the balance.
2. Deliver items from the second account and see the payout.
3. `/order sell all` with a full inventory.
4. Server crash during a delivery and recovery after restart (`kill -9`, start again, `/orderadmin recovery`).
