package dev.orderhub.integration;

import dev.orderhub.api.MarketService;
import dev.orderhub.api.NotificationService;
import dev.orderhub.api.OrderHubApi;
import dev.orderhub.api.OrderService;
import dev.orderhub.api.PlayerOrderService;
import dev.orderhub.api.StatisticsService;
import dev.orderhub.api.TransactionService;
import dev.orderhub.api.event.OrderCancelEvent;
import dev.orderhub.api.event.OrderCreateEvent;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.flow.Services;
import dev.orderhub.item.ItemCodecException;
import dev.orderhub.item.ValidationResult;
import dev.orderhub.tx.JournalEntry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Implementierung der oeffentlichen API. Jede Methode prueft ihre Eingaben wie der interne Weg und
 * laeuft auf dem Async-Scheduler. Drittplugins bekommen nie Datenbankzugriff, nur diese Methoden.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "Die Teil-Services sind unveraenderliche Dienstobjekte dieser Fassade")
public final class ApiImpl implements OrderHubApi {

  private static final int MAX_PAGE = 100;

  private final Services s;
  private final Executor async;

  public ApiImpl(Services services) {
    this.s = services;
    this.async = r -> services.scheduler().runAsync(r);
  }

  private <T> CompletableFuture<T> run(Supplier<T> work) {
    return CompletableFuture.supplyAsync(work, async);
  }

  @Override
  public int apiVersion() {
    return API_VERSION;
  }

  @Override
  public OrderService orders() {
    return orders;
  }

  @Override
  public PlayerOrderService playerOrders() {
    return playerOrders;
  }

  @Override
  public MarketService market() {
    return market;
  }

  @Override
  public StatisticsService statistics() {
    return statistics;
  }

  @Override
  public TransactionService transactions() {
    return transactions;
  }

  @Override
  public NotificationService notifications() {
    return notifications;
  }

  private static OrderService.OrderView view(Order o) {
    return new OrderService.OrderView(
        o.id(),
        o.owner(),
        o.item().itemKey(),
        o.unitPrice(),
        o.quantityTotal(),
        o.quantityFilled(),
        o.escrowRemaining(),
        o.status().name(),
        o.createdAt(),
        o.expiresAt());
  }

  private final OrderService orders =
      new OrderService() {
        @Override
        public CompletableFuture<CreateResult> createOrder(CreateRequest request) {
          if (request == null
              || request.requestId() == null
              || request.owner() == null
              || request.item() == null) {
            return CompletableFuture.completedFuture(
                new CreateResult(CreateStatus.ERROR, null, "request fields missing"));
          }
          // Item-Zustand jetzt kopieren und pruefen, bevor der Aufrufer es veraendern kann.
          ValidationResult check = s.matcher().validateForOrder(request.item().clone());
          if (!check.valid()) {
            return CompletableFuture.completedFuture(
                new CreateResult(CreateStatus.ITEM_REJECTED, null, String.valueOf(check.reason())));
          }
          final ItemSnapshot snapshot;
          try {
            snapshot = s.codec().encode(request.item().clone());
          } catch (ItemCodecException e) {
            return CompletableFuture.completedFuture(
                new CreateResult(CreateStatus.ITEM_REJECTED, null, "unserializable"));
          }
          Duration lifetime =
              request.lifetime() == null
                      || request.lifetime().isNegative()
                      || request.lifetime().isZero()
                  ? Duration.ofHours(s.settings().get().expiryHours())
                  : request.lifetime();
          return run(() -> {
            var result =
                s.orders()
                    .createOrder(
                        new dev.orderhub.service.OrderService.CreateOrderRequest(
                            request.requestId(),
                            request.owner(),
                            snapshot,
                            request.unitPriceMinor(),
                            request.quantity(),
                            1,
                            lifetime,
                            false,
                            "items",
                            s.limits().defaultMaxOpenOrders(),
                            false));
            if (result.status() == dev.orderhub.service.OrderService.CreateStatus.CREATED
                && result.orderId() != null) {
              Events.fire(
                  new OrderCreateEvent(
                      result.orderId(),
                      request.owner(),
                      snapshot.itemKey(),
                      request.quantity(),
                      request.unitPriceMinor()));
              try {
                s.insights()
                    .orderCreated(
                        result.orderId(),
                        request.owner(),
                        snapshot.itemKey(),
                        request.unitPriceMinor(),
                        request.quantity());
              } catch (RuntimeException ignored) {
                // abgeleitete Daten, kein Grund den Auftrag zu melden als fehlgeschlagen
              }
            }
            return new CreateResult(map(result.status()), result.orderId(), result.status().name());
          });
        }

        @Override
        public CompletableFuture<Optional<OrderView>> getOrder(long orderId) {
          return run(() ->
              s.storage().transact(uow -> uow.orders().find(orderId)).join().map(ApiImpl::view));
        }

        @Override
        public CompletableFuture<List<OrderView>> getOpenOrders(Filter filter) {
          Filter f = filter == null ? new Filter(null, null, 0, 45) : filter;
          return run(() ->
              s
                  .storage()
                  .transact(uow ->
                      uow.orders()
                          .search(
                              new OrderQuery(
                                  f.owner(),
                                  f.itemKey(),
                                  null,
                                  OrderStatus.ACTIVE,
                                  OrderQuery.Sort.NEWEST,
                                  Math.max(0, f.offset()),
                                  Math.min(MAX_PAGE, Math.max(1, f.limit())))))
                  .join()
                  .stream()
                  .map(ApiImpl::view)
                  .toList());
        }

        @Override
        public CompletableFuture<CancelResult> cancelOrder(long orderId, UUID actor) {
          if (actor == null) {
            return CompletableFuture.completedFuture(new CancelResult(CancelStatus.ERROR, 0));
          }
          return run(() -> {
            var r = s.orders().cancelOrder(orderId, actor, false);
            if (r.ok()) {
              Events.fire(new OrderCancelEvent(orderId, actor, r.refunded()));
            }
            return new CancelResult(
                switch (r.status()) {
                  case OK -> CancelStatus.CANCELLED;
                  case ALREADY_DONE -> CancelStatus.ALREADY_DONE;
                  case NOT_FOUND -> CancelStatus.NOT_FOUND;
                  case NOT_OWNER -> CancelStatus.NOT_OWNER;
                  case INVALID_STATE, CONFLICT -> CancelStatus.INVALID_STATE;
                },
                r.refunded());
          });
        }
      };

  private static OrderService.CreateStatus map(dev.orderhub.service.OrderService.CreateStatus st) {
    return switch (st) {
      case CREATED -> OrderService.CreateStatus.CREATED;
      case ALREADY_CREATED -> OrderService.CreateStatus.ALREADY_CREATED;
      case INVALID_PRICE -> OrderService.CreateStatus.INVALID_PRICE;
      case INVALID_QUANTITY, INVALID_AMOUNT -> OrderService.CreateStatus.INVALID_QUANTITY;
      case LIMIT_REACHED -> OrderService.CreateStatus.LIMIT_REACHED;
      case INSUFFICIENT_FUNDS -> OrderService.CreateStatus.INSUFFICIENT_FUNDS;
      case ECONOMY_UNAVAILABLE -> OrderService.CreateStatus.ECONOMY_UNAVAILABLE;
      default -> OrderService.CreateStatus.ERROR;
    };
  }

  private final PlayerOrderService playerOrders =
      new PlayerOrderService() {
        @Override
        public CompletableFuture<List<OrderService.OrderView>> getOrders(
            UUID owner, int offset, int limit) {
          return run(() ->
              s
                  .storage()
                  .transact(uow ->
                      uow.orders()
                          .search(
                              new OrderQuery(
                                  owner,
                                  null,
                                  null,
                                  OrderStatus.ACTIVE,
                                  OrderQuery.Sort.NEWEST,
                                  Math.max(0, offset),
                                  Math.min(MAX_PAGE, Math.max(1, limit)))))
                  .join()
                  .stream()
                  .map(ApiImpl::view)
                  .toList());
        }

        @Override
        public CompletableFuture<Integer> countOpenOrders(UUID owner) {
          return run(
              () -> s.storage().transact(uow -> uow.orders().countOpenByOwner(owner)).join());
        }
      };

  private final MarketService market =
      (itemKey, days) ->
          run(() -> {
            int d = Math.max(1, Math.min(days, 90));
            var v = ApiImpl.this.s.insights().market(itemKey, d, System.currentTimeMillis());
            var t = v.trades();
            var dem = v.demand();
            var rec = v.recommendation();
            return new MarketService.MarketStatistics(
                itemKey,
                d,
                t.map(x -> x.trades()).orElse(0),
                t.map(x -> x.volumeUnits()).orElse(0L),
                t.map(x -> x.medianUnitPrice()).orElse(null),
                t.map(x -> x.averageUnitPrice()).orElse(null),
                dem.map(x -> x.orders()).orElse(0),
                dem.map(x -> x.openUnits()).orElse(0L),
                dem.map(x -> x.lowestUnitPrice()).orElse(null),
                dem.map(x -> x.highestUnitPrice()).orElse(null),
                rec.map(x -> x.low()).orElse(null),
                rec.map(x -> x.high()).orElse(null));
          });

  private final StatisticsService statistics =
      player ->
          run(() ->
              ApiImpl.this
                  .s
                  .insights()
                  .stats(player)
                  .map(st ->
                      new StatisticsService.PlayerStatistics(
                          st.player(),
                          st.ordersCreated(),
                          st.ordersCompleted(),
                          st.itemsDelivered(),
                          st.itemsReceived(),
                          st.moneyEarned(),
                          st.moneySpent())));

  private static TransactionService.TransactionView tv(JournalEntry e) {
    return new TransactionService.TransactionView(
        e.id(),
        e.orderId(),
        e.playerId(),
        e.type().name(),
        e.state().name(),
        e.quantity(),
        e.amount(),
        e.createdAt(),
        e.updatedAt());
  }

  private final TransactionService transactions =
      new TransactionService() {
        @Override
        public CompletableFuture<Optional<TransactionView>> getTransaction(UUID id) {
          return run(
              () -> s.storage().transact(uow -> uow.journal().find(id)).join().map(ApiImpl::tv));
        }

        @Override
        public CompletableFuture<List<TransactionView>> getTransactions(UUID player, int limit) {
          return run(() ->
              s
                  .storage()
                  .transact(uow ->
                      uow.journal().findByPlayer(player, 0, Math.min(MAX_PAGE, Math.max(1, limit))))
                  .join()
                  .stream()
                  .map(ApiImpl::tv)
                  .toList());
        }
      };

  private final NotificationService notifications =
      new NotificationService() {
        @Override
        public CompletableFuture<Boolean> addFavorite(UUID player, String itemKey) {
          return run(() -> s.insights().addFavorite(player, itemKey));
        }

        @Override
        public CompletableFuture<Boolean> removeFavorite(UUID player, String itemKey) {
          return run(() -> s.insights().removeFavorite(player, itemKey));
        }

        @Override
        public CompletableFuture<Boolean> setPriceAlert(
            UUID player, String itemKey, Long minPriceMinor) {
          if (minPriceMinor != null && minPriceMinor <= 0) {
            return CompletableFuture.completedFuture(false);
          }
          return run(() -> s.insights().setAlert(player, itemKey, minPriceMinor));
        }

        @Override
        public CompletableFuture<List<FavoriteView>> getFavorites(UUID player) {
          return run(() ->
              s.insights().favorites(player).stream()
                  .map(f -> new FavoriteView(f.itemKey(), f.minPriceAlert()))
                  .toList());
        }
      };
}
