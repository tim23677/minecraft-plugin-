# Quickstart

1. Hold an item and run `/order create 64 8`. This creates an order for 64 items at 8 each and holds 512 plus the creation fee in escrow.
2. A second player holds the item and runs `/order sell`. The delivery menu opens with the best matching order, the player confirms and is paid at once.
3. The buyer runs `/order claims` and collects the items.
4. `/order mine` lists your orders. Left click cancels with refund of the remaining escrow, right click pauses or resumes.
5. `/order market` opens the marketplace with search, categories and sorting.

Administrators: `/orderadmin health` shows the state of the database, the payout queue and the economy latency.
