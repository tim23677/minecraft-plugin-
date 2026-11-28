package dev.orderhub.placeholder;

import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.flow.Services;
import dev.orderhub.money.Money;
import java.util.Locale;
import java.util.UUID;

/**
 * Berechnet die Werte der Platzhalter ohne Abhaengigkeit von PlaceholderAPI, damit sie ohne den
 * Server testbar sind. Antwortet nur aus dem Cache, nie mit einer synchronen Datenbankabfrage.
 */
public final class PlaceholderResolver {

  private final Services s;
  private final PlaceholderCache cache;

  public PlaceholderResolver(Services services, PlaceholderCache cache) {
    this.s = services;
    this.cache = cache;
  }

  /** Wert fuer {@code %orderhub_<params>%}, {@code null} bei unbekanntem Namen. */
  public String resolve(UUID player, String params) {
    String p = params.toLowerCase(Locale.ROOT);
    if (p.startsWith("market_average_")) {
      String item = itemKey(p.substring("market_average_".length()));
      return cache.get(
          "avg:" + item,
          () ->
              s.insights()
                  .market(item, 7, System.currentTimeMillis())
                  .trades()
                  .map(t -> Money.format(t.averageUnitPrice()))
                  .orElse("-"),
          "-");
    }
    if (p.startsWith("market_highest_") || p.startsWith("market_lowest_")) {
      boolean highest = p.startsWith("market_highest_");
      String item =
          itemKey(p.substring(highest ? "market_highest_".length() : "market_lowest_".length()));
      return cache.get(
          (highest ? "high:" : "low:") + item,
          () ->
              s.insights()
                  .market(item, 7, System.currentTimeMillis())
                  .demand()
                  .map(d -> Money.format(highest ? d.highestUnitPrice() : d.lowestUnitPrice()))
                  .orElse("-"),
          "-");
    }
    if (p.startsWith("market_demand_")) {
      String item = itemKey(p.substring("market_demand_".length()));
      return cache.get(
          "demand:" + item,
          () ->
              s.insights()
                  .market(item, 7, System.currentTimeMillis())
                  .demand()
                  .map(d -> Long.toString(d.openUnits()))
                  .orElse("0"),
          "0");
    }
    if (p.startsWith("top_")) {
      return top(p.substring("top_".length()));
    }
    if (p.equals("market_open_orders")) {
      return cache.get(
          "market-open",
          () ->
              Long.toString(
                  s.storage()
                      .transact(u ->
                          u.orders()
                              .count(
                                  new OrderQuery(
                                      null,
                                      null,
                                      null,
                                      OrderStatus.ACTIVE,
                                      OrderQuery.Sort.NEWEST,
                                      0,
                                      1)))
                      .join()),
          "0");
    }
    if (player == null) {
      return "";
    }
    var id = player;
    return switch (p) {
      case "open_orders" ->
          cache.get(
              "open:" + id,
              () ->
                  Integer.toString(
                      s.storage().transact(u -> u.orders().countOpenByOwnerUnlocked(id)).join()),
              "0");
      case "claims_waiting" ->
          cache.get(
              "claims:" + id,
              () ->
                  Integer.toString(s.storage().transact(u -> u.claims().countAvailable(id)).join()),
              "0");
      case "completed_orders" ->
          cache.get(
              "completed:" + id,
              () -> Long.toString(s.insights().stats(id).map(x -> x.ordersCompleted()).orElse(0L)),
              "0");
      case "total_volume" ->
          cache.get(
              "volume:" + id,
              () ->
                  Money.format(
                      s.insights().stats(id).map(x -> x.moneyEarned() + x.moneySpent()).orElse(0L)),
              "0.00");
      case "player_earnings" ->
          cache.get(
              "earn:" + id,
              () -> Money.format(s.insights().stats(id).map(x -> x.moneyEarned()).orElse(0L)),
              "0.00");
      case "player_deliveries" ->
          cache.get(
              "deliv:" + id,
              () -> Long.toString(s.insights().stats(id).map(x -> x.itemsDelivered()).orElse(0L)),
              "0");
      default -> null;
    };
  }

  private static String itemKey(String raw) {
    String cleaned = raw.replaceAll("[^a-z0-9_:]", "");
    return cleaned.contains(":") ? cleaned : "minecraft:" + cleaned;
  }

  /**
   * {@code top_<kennzahl>_<platz>_<name|value>}, Platz 1 bis 10, zum Beispiel {@code
   * top_earned_1_name}.
   */
  private String top(String spec) {
    String[] parts = spec.split("_");
    if (parts.length != 3 || !parts[1].matches("[0-9]{1,2}")) {
      return null;
    }
    dev.orderhub.storage.InsightsRepository.TopStat stat;
    try {
      stat =
          dev.orderhub.storage.InsightsRepository.TopStat.valueOf(
              parts[0].toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
    int rank = Integer.parseInt(parts[1]);
    boolean name = parts[2].equals("name");
    if (rank < 1 || rank > 10 || !(name || parts[2].equals("value"))) {
      return null;
    }
    return cache.get(
        "top:" + stat + ":" + rank + ":" + parts[2],
        () -> {
          var rows = s.insights().top(stat, 10);
          if (rank > rows.size()) {
            return "-";
          }
          var row = rows.get(rank - 1);
          return name
              ? dev.orderhub.flow.TopFlow.bukkitName(row.player())
              : dev.orderhub.flow.TopFlow.format(stat, row.value());
        },
        "-");
  }
}
