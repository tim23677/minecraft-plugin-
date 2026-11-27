package dev.orderhub.placeholder;

import dev.orderhub.flow.Services;
import dev.orderhub.money.Money;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI-Erweiterung {@code %orderhub_...%}. Antwortet nur aus dem Cache, nie mit einer
 * synchronen Datenbankabfrage. Fehlende Werte liefern {@code 0} bzw. {@code -}.
 */
@SuppressFBWarnings(
    value = "HE_INHERITS_EQUALS_USE_HASHCODE",
    justification = "equals ist in PlaceholderExpansion final, Identitaetsvergleich ist gewollt")
public final class OrderHubExpansion extends PlaceholderExpansion {

  private final Services s;
  private final String version;
  private final PlaceholderCache cache;

  public OrderHubExpansion(Services services, String version, PlaceholderCache cache) {
    this.s = services;
    this.version = version;
    this.cache = cache;
  }

  @Override
  public @NotNull String getIdentifier() {
    return "orderhub";
  }

  @Override
  public @NotNull String getAuthor() {
    return "OrderHub";
  }

  @Override
  public @NotNull String getVersion() {
    return version;
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer player, @NotNull String params) {
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
    if (player == null) {
      return "";
    }
    var id = player.getUniqueId();
    return switch (p) {
      case "open_orders" ->
          cache.get(
              "open:" + id,
              () ->
                  Integer.toString(
                      s.storage().transact(u -> u.orders().countOpenByOwner(id)).join()),
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
