package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.gui.Button;
import dev.orderhub.gui.ConfirmMenu;
import dev.orderhub.gui.PagedMenu;
import dev.orderhub.money.Money;
import dev.orderhub.service.OrderService;
import dev.orderhub.storage.ParcelRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** Listenmenues: Marktplatz, eigene Auftraege, Abholung. Daten werden asynchron geladen. */
public final class Menus {

  private static final List<Integer> CONTENT = contentSlots();

  private final Services s;
  private final DeliveryFlow delivery;
  private final ParcelFlow claims;

  public Menus(Services services, DeliveryFlow delivery, ParcelFlow claims) {
    this.s = services;
    this.delivery = delivery;
    this.claims = claims;
  }

  private static List<Integer> contentSlots() {
    List<Integer> slots = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      slots.add(i);
    }
    return List.copyOf(slots);
  }

  private ItemStack arrow(String key) {
    ItemStack stack = new ItemStack(Material.ARROW);
    stack.editMeta(meta -> meta.displayName(s.messages().get(key)));
    return stack;
  }

  private ItemStack named(Material material, Component name) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> meta.displayName(name));
    return stack;
  }

  private volatile MarketFlow market;

  /** Verdrahtet den Marktplatz mit Suche, Kategorien und Keyset-Paginierung. */
  public void market(MarketFlow flow) {
    this.market = flow;
  }

  /** Marktplatz mit Filter (Suche, Kategorie, Sortierung). */
  public void openMarket(Player player, MarketFlow.Filter filter) {
    MarketFlow flow = market;
    if (flow == null) {
      openMarket(player, filter.sort());
      return;
    }
    flow.open(player, filter);
  }

  /** Marktplatz: alle aktiven Auftraege nach {@code sort}, Klick oeffnet das Liefermenue. */
  public void openMarket(Player player, OrderQuery.Sort sort) {
    if (market != null) {
      market.open(player, new MarketFlow.Filter(sort, null, null));
      return;
    }
    OrderQuery query = new OrderQuery(null, null, null, OrderStatus.ACTIVE, sort, 0, 100);
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.storage().transact(uow -> uow.orders().search(query)).join(),
        orders -> {
          PagedMenu<Order> menu =
              new PagedMenu<>(
                  s.messages().get("menu.marketplace-title"),
                  6,
                  CONTENT,
                  45,
                  53,
                  arrow("menu.previous"),
                  arrow("menu.next"),
                  order ->
                      Button.of(
                          orderIcon(order),
                          ctx -> {
                            ctx.player().closeInventory();
                            delivery.open(ctx.player(), order.id());
                          }));
          menu.setItems(orders);
          s.menus().open(player, menu);
        },
        orphan -> {});
  }

  /** Eigene Auftraege. Links: stornieren (mit Bestaetigung). Rechts: pausieren oder fortsetzen. */
  public void openMine(Player player) {
    UUID id = player.getUniqueId();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () ->
            s.storage()
                .transact(uow -> {
                  List<Order> all = new ArrayList<>();
                  for (OrderStatus st : List.of(OrderStatus.ACTIVE, OrderStatus.PAUSED)) {
                    all.addAll(
                        uow.orders()
                            .search(
                                new OrderQuery(
                                    id, null, null, st, OrderQuery.Sort.NEWEST, 0, 100)));
                  }
                  all.sort(Comparator.comparingLong(Order::id).reversed());
                  return all;
                })
                .join(),
        orders -> {
          PagedMenu<Order> menu =
              new PagedMenu<>(
                  s.messages().get("menu.my-orders-title"),
                  6,
                  CONTENT,
                  45,
                  53,
                  arrow("menu.previous"),
                  arrow("menu.next"),
                  order ->
                      Button.of(
                          orderIcon(order),
                          ctx -> onMineClick(ctx.player(), ctx.clickType(), order)));
          menu.setItems(orders);
          s.menus().open(player, menu);
        },
        orphan -> {});
  }

  private void onMineClick(Player player, ClickType click, Order order) {
    if (click == ClickType.RIGHT) {
      boolean pause = order.status() == OrderStatus.ACTIVE;
      s.scheduler()
          .runAsync(() -> {
            OrderService.ActionResult r =
                pause
                    ? s.orders().pause(order.id(), player.getUniqueId(), false)
                    : s.orders().resume(order.id(), player.getUniqueId(), false);
            s.scheduler()
                .tryRunOn(
                    player,
                    () ->
                        player.sendMessage(
                            s.messages()
                                .prefixed(
                                    r.ok()
                                        ? (pause ? "order.paused" : "order.resumed")
                                        : "order.not-active",
                                    Messages.number("id", order.id()))));
          });
      player.closeInventory();
      return;
    }
    ConfirmMenu confirm =
        new ConfirmMenu(
            s.messages().get("menu.confirm-title"),
            named(Material.LIME_STAINED_GLASS_PANE, s.messages().get("menu.confirm")),
            named(Material.RED_STAINED_GLASS_PANE, s.messages().get("menu.cancel")),
            p -> cancel(p, order.id()),
            id -> {});
    s.menus().open(player, confirm);
  }

  /** Storniert einen eigenen Auftrag und meldet das Ergebnis. */
  public void cancel(Player player, long orderId) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.orders().cancelOrder(orderId, player.getUniqueId(), false),
        r -> {
          if (r.ok()) {
            s.scheduler()
                .runAsync(() -> {
                  dev.orderhub.integration.Events.fire(
                      new dev.orderhub.api.event.OrderCancelEvent(
                          orderId, player.getUniqueId(), r.refunded()));
                  s.discord()
                      .publish(
                          dev.orderhub.discord.DiscordService.Event.ORDER_CANCELLED,
                          "Order #" + orderId + " cancelled",
                          "Cancelled by its owner, remaining escrow refunded");
                });
          }
          player.sendMessage(
              switch (r.status()) {
                case OK ->
                    s.messages()
                        .prefixed(
                            "order.cancelled",
                            Messages.number("id", orderId),
                            Messages.text("refund", Money.format(r.refunded())));
                case NOT_FOUND ->
                    s.messages().prefixed("order.not-found", Messages.number("id", orderId));
                case NOT_OWNER -> s.messages().prefixed("order.not-yours");
                default -> s.messages().prefixed("order.not-active");
              });
        },
        orphan -> {});
  }

  /** Abholmenue: Klick holt das Paket ab. */
  public void openClaims(Player player) {
    claims.list(
        player,
        0,
        45,
        parcels -> {
          if (parcels.isEmpty()) {
            player.sendMessage(s.messages().prefixed("claim.nothing"));
            return;
          }
          PagedMenu<ParcelRepository.Parcel> menu =
              new PagedMenu<>(
                  s.messages().get("menu.claims-title"),
                  6,
                  CONTENT,
                  45,
                  53,
                  arrow("menu.previous"),
                  arrow("menu.next"),
                  parcel ->
                      Button.of(
                          parcelIcon(parcel),
                          ctx ->
                              claims.claim(
                                  ctx.player(), parcel, ok -> ctx.player().closeInventory())));
          menu.setItems(parcels);
          s.menus().open(player, menu);
        });
  }

  private ItemStack parcelIcon(ParcelRepository.Parcel parcel) {
    Optional<ItemStack> stack = ItemService.decode(s.codec(), parcel.item());
    ItemStack icon = stack.orElseGet(() -> new ItemStack(Material.BARRIER));
    icon.setAmount(Math.max(1, Math.min(parcel.quantity(), icon.getMaxStackSize())));
    icon.editMeta(meta ->
        meta.lore(
            List.of(
                s.messages()
                    .get("lore.claim-quantity", Messages.number("quantity", parcel.quantity())))));
    return icon;
  }

  public ItemStack orderIcon(Order order) {
    Optional<ItemStack> stack = ItemService.decode(s.codec(), order.item());
    ItemStack icon = stack.orElseGet(() -> new ItemStack(Material.BARRIER));
    icon.setAmount(1);
    String owner =
        Optional.ofNullable(Bukkit.getOfflinePlayer(order.owner()).getName()).orElse("?");
    long left = Math.max(0, order.expiresAt() - System.currentTimeMillis());
    int percent =
        order.quantityTotal() == 0
            ? 0
            : (int) (100L * order.quantityFilled() / order.quantityTotal());
    icon.editMeta(meta ->
        meta.lore(
            List.of(
                s.messages()
                    .get("lore.order-unit", Messages.text("unit", Money.format(order.unitPrice()))),
                s.messages()
                    .get(
                        "lore.order-progress",
                        Messages.number("filled", order.quantityFilled()),
                        Messages.number("total", order.quantityTotal()),
                        Messages.number("percent", percent)),
                s.messages().get("lore.order-owner", Messages.text("owner", owner)),
                s.messages()
                    .get(
                        "lore.order-escrow",
                        Messages.text("escrow", Money.format(order.escrowRemaining()))),
                s.messages().get("lore.order-min", Messages.number("min", order.minDelivery())),
                s.messages()
                    .get(
                        "lore.order-expires",
                        Messages.text("time", format(Duration.ofMillis(left)))))));
    return icon;
  }

  static String format(Duration d) {
    long hours = d.toHours();
    if (hours >= 48) {
      return (hours / 24) + "d";
    }
    if (hours >= 1) {
      return hours + "h " + (d.toMinutesPart()) + "m";
    }
    return d.toMinutes() + "m";
  }
}
