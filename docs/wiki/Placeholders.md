# Placeholders (PlaceholderAPI)

`%orderhub_open_orders%`, `%orderhub_completed_orders%`, `%orderhub_total_volume%`, `%orderhub_player_earnings%`, `%orderhub_player_deliveries%`, `%orderhub_market_average_<item>%`, `%orderhub_market_demand_<item>%`.

Values come from a cache (`cache.max-entries`). Until a value has been loaded the placeholder shows `0` or `-`. The database is never queried on the server thread.
