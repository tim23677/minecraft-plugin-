package dev.orderhub.config;

import dev.orderhub.bootstrap.Settings;
import java.time.Duration;
import java.util.List;

/**
 * Unveraenderlicher Schnappschuss aller Werte, die Geld und Limits bestimmen. Bei {@code reload}
 * wird der ganze Schnappschuss atomar ausgetauscht. Jede Operation liest ihn genau einmal, so dass
 * angezeigte und abgerechnete Werte aus demselben Stand stammen.
 */
public record Rates(Limits limits, Fees fees, Expiry expiry) {

  /** Staffeln der Auftragslimits ({@code orderhub.limit.*}). */
  private static final List<Limits.Tier> TIERS =
      List.of(
          new Limits.Tier("orderhub.limit.5", 5),
          new Limits.Tier("orderhub.limit.10", 10),
          new Limits.Tier("orderhub.limit.25", 25),
          new Limits.Tier("orderhub.limit.50", 50));

  public static Rates from(Settings st) {
    return new Rates(
        new Limits(
            st.minUnitPrice(),
            st.maxUnitPrice(),
            st.maxQuantity(),
            st.defaultMaxOpenOrders(),
            TIERS,
            st.rules().priceBands(),
            new Limits.Daily(st.dailyOrderLimit(), st.dailyDeliveryItems())),
        new Fees(
            st.createFeeBp(),
            st.salesTaxBp(),
            true,
            st.rules().feeTiers(),
            st.rules().feeWindows(),
            st.rules().zone()),
        new Expiry(Duration.ofHours(st.expiryHours()), true));
  }
}
