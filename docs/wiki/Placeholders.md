# Placeholders (PlaceholderAPI)

`%orderhub_open_orders%`, `%orderhub_completed_orders%`, `%orderhub_total_volume%`, `%orderhub_player_earnings%`, `%orderhub_player_deliveries%`, `%orderhub_market_average_<item>%`, `%orderhub_market_demand_<item>%`, `%orderhub_market_open_orders%` (open orders on the whole market), `%orderhub_market_highest_<item>%` and `%orderhub_market_lowest_<item>%` (highest and lowest unit price among the open orders of an item), `%orderhub_claims_waiting%` (parcels waiting for the player).

Values come from a cache (`cache.max-entries`). Until a value has been loaded the placeholder shows `0` or `-`. The database is never queried on the server thread.
