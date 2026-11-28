package dev.orderhub.config;

import dev.orderhub.money.Money;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Grenzen fuer Auftraege. Die Auftragslimits je Permission gelten nach der Regel "hoechstes
 * zutreffendes Limit gewinnt"; das Standardlimit gilt immer als Untergrenze.
 */
public record Limits(
    long minPrice,
    long maxPrice,
    int maxQuantity,
    int defaultMaxOpenOrders,
    List<Tier> tiers,
    Map<String, PriceBand> priceBands,
    Daily daily) {

  /**
   * Tageslimits je Spieler in einem gleitenden 24-Stunden-Fenster, 0 = kein Limit.
   *
   * @param orders neue Auftraege
   * @param deliveryItems gelieferte Items
   */
  public record Daily(int orders, int deliveryItems) {
    public Daily {
      if (orders < 0 || deliveryItems < 0) {
        throw new IllegalArgumentException("daily limits");
      }
    }

    public static final Daily NONE = new Daily(0, 0);
  }

  /**
   * Preisgrenzen eines einzelnen Items, zusaetzlich zu den allgemeinen Grenzen.
   *
   * @param min kleinster erlaubter Stueckpreis
   * @param max groesster erlaubter Stueckpreis
   */
  public record PriceBand(long min, long max) {
    public PriceBand {
      if (min < 1 || max < min || max > Money.MAX) {
        throw new IllegalArgumentException("price band");
      }
    }

    public boolean contains(long price) {
      return price >= min && price <= max;
    }
  }

  /** Ohne Preisgrenzen je Item. */
  public Limits(
      long minPrice, long maxPrice, int maxQuantity, int defaultMaxOpenOrders, List<Tier> tiers) {
    this(minPrice, maxPrice, maxQuantity, defaultMaxOpenOrders, tiers, Map.of(), Daily.NONE);
  }

  /** Ohne Tageslimits. */
  public Limits(
      long minPrice,
      long maxPrice,
      int maxQuantity,
      int defaultMaxOpenOrders,
      List<Tier> tiers,
      Map<String, PriceBand> priceBands) {
    this(minPrice, maxPrice, maxQuantity, defaultMaxOpenOrders, tiers, priceBands, Daily.NONE);
  }

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
    priceBands = Map.copyOf(priceBands);
    daily = daily == null ? Daily.NONE : daily;
  }

  /** Preisgrenze fuer das Item oder {@code null}, wenn keine konfiguriert ist. */
  public PriceBand priceBand(String itemKey) {
    return priceBands.get(itemKey);
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
