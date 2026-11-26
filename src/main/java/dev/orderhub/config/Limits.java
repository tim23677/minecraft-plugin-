package dev.orderhub.config;

import dev.orderhub.money.Money;
import java.util.List;
import java.util.function.Predicate;

/**
 * Grenzen fuer Auftraege. Die Auftragslimits je Permission gelten nach der Regel "hoechstes
 * zutreffendes Limit gewinnt"; das Standardlimit gilt immer als Untergrenze.
 */
public record Limits(
    long minPrice, long maxPrice, int maxQuantity, int defaultMaxOpenOrders, List<Tier> tiers) {

  /** Staffelstufe: Permission und zugehoeriges Limit offener Auftraege. */
  public record Tier(String permission, int maxOpenOrders) {
    public Tier {
      if (permission == null || permission.isBlank()) {
        throw new IllegalArgumentException("permission");
      }
      if (maxOpenOrders < 1) {
        throw new IllegalArgumentException("maxOpenOrders");
      }
    }
  }

  public Limits {
    if (minPrice < 1 || maxPrice < minPrice || maxPrice > Money.MAX) {
      throw new IllegalArgumentException("price range");
    }
    if (maxQuantity < 1 || defaultMaxOpenOrders < 1) {
      throw new IllegalArgumentException("limits must be positive");
    }
    tiers = List.copyOf(tiers);
  }

  /** Standardstaffelung 5/10/25/50. */
  public static Limits defaults() {
    return new Limits(
        1L,
        Money.MAX,
        2_304,
        3,
        List.of(
            new Tier("orderhub.orders.5", 5),
            new Tier("orderhub.orders.10", 10),
            new Tier("orderhub.orders.25", 25),
            new Tier("orderhub.orders.50", 50)));
  }

  /** Effektives Limit offener Auftraege: Maximum aus Standard und allen zutreffenden Stufen. */
  public int maxOpenOrders(Predicate<String> hasPermission) {
    int best = defaultMaxOpenOrders;
    for (Tier tier : tiers) {
      if (tier.maxOpenOrders() > best && hasPermission.test(tier.permission())) {
        best = tier.maxOpenOrders();
      }
    }
    return best;
  }
}
