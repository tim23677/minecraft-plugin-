# OrderHub Developer API

Package `dev.orderhub.api`. Version 1 (`OrderHubApi.API_VERSION`).

## Getting the API

```java
RegisteredServiceProvider<OrderHubApi> rsp =
    Bukkit.getServicesManager().getRegistration(OrderHubApi.class);
if (rsp == null) {
  return; // OrderHub is not installed or not enabled
}
OrderHubApi api = rsp.getProvider();
```

Declare `softdepend: [OrderHub]` (or `depend`) in your plugin descriptor and compile against the OrderHub jar with `compileOnly`.

## Rules for callers

- Every method returns a `CompletableFuture` and may be called from any thread. Results are produced on OrderHub's async scheduler, never on the server thread.
- Do not touch players, worlds or inventories inside a future callback unless you first switch to the right Folia scheduler (entity, region or global).
- Amounts are `long` in minor units (two decimals, 1.00 equals 100).
- There is no database access for other plugins. The API validates input the same way the in-game commands do.
- Compatibility: interfaces and records are only extended. New methods are added as `default` methods. Check `apiVersion()` if you need a newer feature.
- Plugins calling `createOrder` pass a `requestId`. The same id never creates a second order (idempotency).

## Services

| Service | Methods |
|---|---|
| `OrderService` | `createOrder(CreateRequest)`, `getOrder(long)`, `getOpenOrders(Filter)`, `cancelOrder(long, UUID actor)` |
| `PlayerOrderService` | `getOrders(UUID, offset, limit)`, `countOpenOrders(UUID)` |
| `MarketService` | `getMarketStatistics(itemKey, days)` |
| `StatisticsService` | `getPlayerStatistics(UUID)` |
| `TransactionService` | `getTransaction(UUID)`, `getTransactions(UUID, limit)` (read only) |
| `NotificationService` | `addFavorite`, `removeFavorite`, `setPriceAlert`, `getFavorites` |

Errors: methods do not throw for expected problems. Creation returns a `CreateStatus` (`ITEM_REJECTED`, `INVALID_PRICE`, `LIMIT_REACHED`, `INSUFFICIENT_FUNDS`, `ECONOMY_UNAVAILABLE`, `ERROR` and so on), cancellation returns a `CancelStatus`. Unexpected failures complete the future exceptionally.

Limitations of version 1: `createOrder` applies the default open order limit of the config (it cannot read permissions of offline players), uses a minimum delivery of 1 and does not enable auto renewal.

## Example

```java
api.orders()
    .createOrder(new OrderService.CreateRequest(
        UUID.randomUUID(), player.getUniqueId(), new ItemStack(Material.DIAMOND), 64, 800L, Duration.ofDays(3)))
    .thenAccept(result -> getLogger().info("create: " + result.status() + " id=" + result.orderId()));

api.market()
    .getMarketStatistics("minecraft:diamond", 7)
    .thenAccept(stats -> getLogger().info("median " + stats.medianTradePriceMinor()));
```

`MarketStatistics` fields are `null` when there is no data. OrderHub never invents prices.

## Events

All events are asynchronous and informational (not cancellable, because money and items have already moved when they fire). Register listeners as usual. Do not access the world or entities in them without switching scheduler.

| Event | Fired when |
|---|---|
| `OrderCreateEvent` | an order was created and its escrow reserved |
| `OrderDeliveryEvent` | a delivery was committed (payout follows asynchronously) |
| `OrderCompleteEvent` | the last delivery completed an order |
| `OrderCancelEvent` | an order was cancelled by the owner, an admin or the API |
| `OrderExpireEvent` | an order expired |
| `TransactionStateChangeEvent` | an order creation or delivery reached `CREATED` or `COMMITTED` in the journal (not every internal payout state) |
