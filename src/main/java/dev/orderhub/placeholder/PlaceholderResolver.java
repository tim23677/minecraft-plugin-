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
}
