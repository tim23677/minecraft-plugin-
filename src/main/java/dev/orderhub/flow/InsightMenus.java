package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.gui.Button;
import dev.orderhub.gui.ControlledMenu;
import dev.orderhub.gui.PagedMenu;
import dev.orderhub.money.Money;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.InsightsRepository;
import dev.orderhub.tx.JournalEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * Statistik, Verlauf, Favoriten und Marktanalyse als Menues statt Chatzeilen. Die Daten kommen aus
 * denselben Diensten wie die Chatausgabe, geladen wird asynchron.
 *
 * @since 2.0.0
 */
public final class InsightMenus {

  private static final int[] WINDOWS = {1, 7, 30, 90};
  private static final List<Integer> CONTENT = range(45);

  private final Services s;

  public InsightMenus(Services services) {
    this.s = services;
  }

  private static List<Integer> range(int n) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(i);
    }
    return List.copyOf(out);
  }

  private ItemStack icon(Material material, Component name, List<Component> lore) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> {
      meta.displayName(name);
      meta.lore(lore);
    });
    return stack;
  }

  private ItemStack arrow(String key) {
    return icon(Material.ARROW, s.messages().get(key), List.of());
  }

  /** Material zu einem Item-Key, sonst ein neutrales Symbol (Items anderer Plugins). */
  static Material materialOf(String itemKey) {
    Material found = Material.matchMaterial(itemKey == null ? "" : itemKey);
    return found == null || found.isAir() || !found.isItem() ? Material.PAPER : found;
  }

  // ---------------------------------------------------------------- Statistik

  public void stats(Player player) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().stats(player.getUniqueId()),
        stats -> {
          if (stats.isEmpty()) {
            player.sendMessage(s.messages().prefixed("stats.empty"));
            return;
          }
          InsightsRepository.PlayerStats st = stats.get();
          long total = st.ordersCreated();
          int rate = total == 0 ? 0 : (int) Math.min(100, 100L * st.ordersCompleted() / total);
          ControlledMenu menu = new ControlledMenu(s.messages().get("menu.statistics-title"), 4);
          put(menu, 10, Material.WRITABLE_BOOK, "created", Long.toString(st.ordersCreated()));
          put(menu, 11, Material.EMERALD, "completed", st.ordersCompleted() + " (" + rate + " %)");
          put(menu, 13, Material.CHEST, "delivered", Long.toString(st.itemsDelivered()));
          put(menu, 14, Material.HOPPER, "received", Long.toString(st.itemsReceived()));
          put(menu, 15, Material.GOLD_INGOT, "earned", Money.format(st.moneyEarned()));
          put(menu, 16, Material.REDSTONE, "spent", Money.format(st.moneySpent()));
          s.menus().open(player, menu);
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private void put(ControlledMenu menu, int slot, Material material, String key, String value) {
    menu.put(
        slot,
        Button.decoration(
            icon(
                material,
                s.messages().get("insight.stats." + key),
                List.of(s.messages().get("insight.value", Messages.text("value", value))))));
  }

  // ---------------------------------------------------------------- Verlauf

  public void history(Player player) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().deliveryHistory(player.getUniqueId(), 100),
        entries -> {
          if (entries.isEmpty()) {
            player.sendMessage(s.messages().prefixed("history.empty"));
            return;
          }
          PagedMenu<JournalEntry> menu =
              new PagedMenu<>(
                  s.messages().get("menu.history-title"),
                  6,
                  CONTENT,
                  45,
                  53,
                  arrow("menu.previous"),
                  arrow("menu.next"),
                  e ->
                      Button.decoration(
                          icon(
                              Material.PAPER,
                              s.messages()
                                  .get(
                                      "insight.history.title",
                                      Messages.number(
                                          "order", e.orderId() == null ? 0 : e.orderId())),
                              List.of(
                                  s.messages()
                                      .get(
                                          "history.line",
                                          Messages.number(
                                              "order", e.orderId() == null ? 0 : e.orderId()),
                                          Messages.number("quantity", e.quantity()),
                                          Messages.text("amount", Money.format(e.amount())),
                                          Messages.text("state", e.state().name()))))));
          menu.setItems(entries);
          s.menus().open(player, menu);
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  // ---------------------------------------------------------------- Favoriten

  public void favorites(Player player) {
    UUID id = player.getUniqueId();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().favorites(id),
        list -> {
          if (list.isEmpty()) {
            player.sendMessage(s.messages().prefixed("favorites.empty"));
            return;
          }
          PagedMenu<InsightsRepository.Favorite> menu =
              new PagedMenu<>(
                  s.messages().get("menu.favorites-title"),
                  6,
                  CONTENT,
                  45,
                  53,
                  arrow("menu.previous"),
                  arrow("menu.next"),
                  f ->
                      Button.of(
                          icon(
                              materialOf(f.itemKey()),
                              Component.text(f.itemKey()),
                              List.of(
                                  s.messages()
                                      .get(
                                          "favorites.line",
                                          Messages.text("item", f.itemKey()),
                                          Messages.text(
                                              "alert",
                                              f.minPriceAlert() == null
                                                  ? "-"
                                                  : Money.format(f.minPriceAlert()))),
                                  s.messages().get("insight.favorites.hint"))),
                          ctx -> onFavoriteClick(ctx.player(), ctx.clickType(), f)));
          menu.setItems(list);
          s.menus().open(player, menu);
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private void onFavoriteClick(Player player, ClickType click, InsightsRepository.Favorite f) {
    player.closeInventory();
    if (click == ClickType.RIGHT) {
      Async.supply(
          s.scheduler(),
          s.logger(),
          player,
          () -> s.insights().removeFavorite(player.getUniqueId(), f.itemKey()),
          ok -> {
            player.sendMessage(
                s.messages()
                    .prefixed(
                        ok ? "favorites.removed" : "favorites.unchanged",
                        Messages.text("item", f.itemKey())));
            favorites(player);
          },
          orphan -> {});
      return;
    }
    analytics(player, f.itemKey());
  }

  // ---------------------------------------------------------------- Marktanalyse

  /**
   * Analyse eines Items als ein Symbol mit Nachfrage, Preisverlauf je Zeitfenster und Empfehlung.
   */
  public void analytics(Player player, String itemKey) {
    long now = System.currentTimeMillis();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> {
          List<InsightsService.MarketView> views = new ArrayList<>();
          for (int days : WINDOWS) {
            views.add(s.insights().market(itemKey, days, now));
          }
          return views;
        },
        views -> showAnalytics(player, itemKey, views),
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private void showAnalytics(
      Player player, String itemKey, List<InsightsService.MarketView> views) {
    List<Component> lore = new ArrayList<>();
    var demand = views.get(0).demand();
    if (demand.isPresent()) {
      var d = demand.get();
      lore.add(
          s.messages()
              .get(
                  "analytics.demand",
                  Messages.number("orders", d.orders()),
                  Messages.number("units", d.openUnits()),
                  Messages.text("low", Money.format(d.lowestUnitPrice())),
                  Messages.text("high", Money.format(d.highestUnitPrice())),
                  Messages.text("median", Money.format(d.medianUnitPrice())),
                  Messages.text("avg", Money.format(d.averageUnitPrice()))));
    } else {
      lore.add(s.messages().get("analytics.no-demand"));
    }
    for (InsightsService.MarketView v : views) {
      if (v.trades().isEmpty()) {
        lore.add(s.messages().get("analytics.no-trades", Messages.number("days", v.days())));
        continue;
      }
      var t = v.trades().get();
      lore.add(
          s.messages()
              .get(
                  "analytics.trades",
                  Messages.number("days", v.days()),
                  Messages.number("trades", t.trades()),
                  Messages.number("units", t.volumeUnits()),
                  Messages.text("volume", Money.format(t.volumeMoney())),
                  Messages.text("median", Money.format(t.medianUnitPrice())),
                  Messages.text("avg", Money.format(t.averageUnitPrice()))));
    }
    var week = views.get(1);
    if (week.recommendation().isPresent()) {
      var r = week.recommendation().get();
      lore.add(
          s.messages()
              .get(
                  "market.recommendation",
                  Messages.text("item", itemKey),
                  Messages.text("low", Money.format(r.low())),
                  Messages.text("high", Money.format(r.high())),
                  Messages.number("trades", r.trades()),
                  Messages.number("days", week.days())));
    } else {
      lore.add(s.messages().get("market.no-data"));
    }
    ControlledMenu menu = new ControlledMenu(s.messages().get("menu.analytics-title"), 3);
    menu.put(
        13,
        Button.decoration(
            icon(materialOf(itemKey), Component.text(itemKey.toLowerCase(Locale.ROOT)), lore)));
    s.menus().open(player, menu);
  }
}
