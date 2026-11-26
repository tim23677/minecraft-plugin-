package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.money.Money;
import dev.orderhub.service.InsightsService;
import dev.orderhub.storage.InsightsRepository;
import dev.orderhub.tx.JournalEntry;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Chat-Ausgaben fuer Statistik, Verlauf, Favoriten und Marktanalyse. */
public final class InsightsFlow {

  private static final int[] WINDOWS = {1, 7, 30, 90};

  private final Services s;

  public InsightsFlow(Services services) {
    this.s = services;
  }

  public void showStats(Player player) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().stats(player.getUniqueId()),
        stats -> {
          player.sendMessage(s.messages().get("stats.header"));
          if (stats.isEmpty()) {
            player.sendMessage(s.messages().prefixed("stats.empty"));
            return;
          }
          InsightsRepository.PlayerStats st = stats.get();
          long total = st.ordersCreated();
          int rate = total == 0 ? 0 : (int) Math.min(100, 100L * st.ordersCompleted() / total);
          player.sendMessage(
              s.messages()
                  .get(
                      "stats.line",
                      Messages.number("created", st.ordersCreated()),
                      Messages.number("completed", st.ordersCompleted()),
                      Messages.number("rate", rate),
                      Messages.number("delivered", st.itemsDelivered()),
                      Messages.number("received", st.itemsReceived()),
                      Messages.text("earned", Money.format(st.moneyEarned())),
                      Messages.text("spent", Money.format(st.moneySpent()))));
        },
        orphan -> {});
  }

  public void showHistory(Player player) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.insights().deliveryHistory(player.getUniqueId(), 10),
        entries -> {
          player.sendMessage(s.messages().get("history.header"));
          if (entries.isEmpty()) {
            player.sendMessage(s.messages().prefixed("history.empty"));
          }
          for (JournalEntry e : entries) {
            player.sendMessage(
                s.messages()
                    .get(
                        "history.line",
                        Messages.number("order", e.orderId() == null ? 0 : e.orderId()),
                        Messages.number("quantity", e.quantity()),
                        Messages.text("amount", Money.format(e.amount())),
                        Messages.text("state", e.state().name())));
          }
        },
        orphan -> {});
  }

  /** {@code /order favorites [add|remove|alert <price>|alert-off]}, das Item kommt aus der Hand. */
  public void favorites(Player player, String[] args) {
    if (args.length <= 1) {
      Async.supply(
          s.scheduler(),
          s.logger(),
          player,
          () -> s.insights().favorites(player.getUniqueId()),
          list -> {
            player.sendMessage(s.messages().get("favorites.header"));
            if (list.isEmpty()) {
              player.sendMessage(s.messages().prefixed("favorites.empty"));
            }
            for (InsightsRepository.Favorite f : list) {
              player.sendMessage(
                  s.messages()
                      .get(
                          "favorites.line",
                          Messages.text("item", f.itemKey()),
                          Messages.text(
                              "alert",
                              f.minPriceAlert() == null ? "-" : Money.format(f.minPriceAlert()))));
            }
          },
          orphan -> {});
      return;
    }
    ItemStack hand = player.getInventory().getItemInMainHand();
    if (hand.getType() == Material.AIR) {
      player.sendMessage(s.messages().prefixed("reject.air"));
      return;
    }
    String key = hand.getType().getKey().toString();
    Component name = hand.effectiveName();
    String action = args[1].toLowerCase(Locale.ROOT);
    switch (action) {
      case "add" ->
          async(
              player,
              () -> s.insights().addFavorite(player.getUniqueId(), key),
              "favorites.added",
              name);
      case "remove" ->
          async(
              player,
              () -> s.insights().removeFavorite(player.getUniqueId(), key),
              "favorites.removed",
              name);
      case "alert" -> {
        if (args.length < 3) {
          player.sendMessage(
              s.messages().prefixed("general.invalid-amount", Messages.text("input", "")));
          return;
        }
        final long price;
        try {
          price = Money.parse(args[2]);
        } catch (NumberFormatException e) {
          player.sendMessage(
              s.messages().prefixed("general.invalid-amount", Messages.text("input", args[2])));
          return;
        }
        if (price <= 0) {
          player.sendMessage(
              s.messages().prefixed("general.invalid-amount", Messages.text("input", args[2])));
          return;
        }
        Async.supply(
            s.scheduler(),
            s.logger(),
            player,
            () -> s.insights().setAlert(player.getUniqueId(), key, price),
            ok ->
                player.sendMessage(
                    s.messages()
                        .prefixed(
                            ok ? "favorites.alert-set" : "favorites.not-a-favorite",
                            Messages.component("item", name),
                            Messages.text("price", Money.format(price)))),
            orphan -> {});
      }
      case "alert-off" ->
          async(
              player,
              () -> s.insights().setAlert(player.getUniqueId(), key, null),
              "favorites.alert-off",
              name);
      default -> player.sendMessage(s.messages().prefixed("general.unknown-subcommand"));
    }
  }

  private void async(
      Player player, java.util.function.Supplier<Boolean> work, String key, Component name) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        work,
        ok ->
            player.sendMessage(
                s.messages()
                    .prefixed(ok ? key : "favorites.unchanged", Messages.component("item", name))),
        orphan -> {});
  }

  /** {@code /order analytics [item]}: Datengrundlage immer mit angeben. */
  public void analytics(Player player, String itemArg) {
    ItemStack hand = player.getInventory().getItemInMainHand();
    String key;
    if (itemArg != null) {
      key =
          itemArg.contains(":")
              ? itemArg.toLowerCase(Locale.ROOT)
              : "minecraft:" + itemArg.toLowerCase(Locale.ROOT);
    } else if (hand.getType() != Material.AIR) {
      key = hand.getType().getKey().toString();
    } else {
      key = null;
    }
    if (key == null) {
      Async.supply(
          s.scheduler(),
          s.logger(),
          player,
          () -> s.insights().topItems(10),
          items -> {
            player.sendMessage(s.messages().get("analytics.top-header"));
            for (String item : items) {
              player.sendMessage(
                  s.messages().get("analytics.top-line", Messages.text("item", item)));
            }
          },
          orphan -> {});
      return;
    }
    final String itemKey = key;
    long now = System.currentTimeMillis();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> {
          java.util.List<InsightsService.MarketView> views = new java.util.ArrayList<>();
          for (int days : WINDOWS) {
            views.add(s.insights().market(itemKey, days, now));
          }
          return views;
        },
        views -> render(player, itemKey, views),
        orphan -> {});
  }

  private void render(Player player, String itemKey, List<InsightsService.MarketView> views) {
    player.sendMessage(s.messages().get("analytics.header", Messages.text("item", itemKey)));
    var demand = views.get(0).demand();
    if (demand.isPresent()) {
      var d = demand.get();
      player.sendMessage(
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
      player.sendMessage(s.messages().get("analytics.no-demand"));
    }
    for (InsightsService.MarketView v : views) {
      if (v.trades().isEmpty()) {
        player.sendMessage(
            s.messages().get("analytics.no-trades", Messages.number("days", v.days())));
        continue;
      }
      var t = v.trades().get();
      player.sendMessage(
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
      player.sendMessage(
          s.messages()
              .prefixed(
                  "market.recommendation",
                  Messages.text("item", itemKey),
                  Messages.text("low", Money.format(r.low())),
                  Messages.text("high", Money.format(r.high())),
                  Messages.number("trades", r.trades()),
                  Messages.number("days", week.days())));
    } else {
      player.sendMessage(s.messages().prefixed("market.no-data"));
    }
  }
}
