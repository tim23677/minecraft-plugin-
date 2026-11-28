package dev.orderhub.config;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Zusatzregeln aus der Konfiguration: Preisgrenzen je Item, Gebuehrenstufen und Zeitfenster.
 *
 * @since 2.0.0
 */
public record Rules(
    Map<String, Limits.PriceBand> priceBands,
    List<Fees.Tier> feeTiers,
    List<Fees.Window> feeWindows,
    ZoneId zone) {

  public Rules {
    priceBands = Map.copyOf(priceBands);
    feeTiers = List.copyOf(feeTiers);
    feeWindows = List.copyOf(feeWindows);
    zone = zone == null ? ZoneId.systemDefault() : zone;
  }

  public static Rules none() {
    return new Rules(Map.of(), List.of(), List.of(), ZoneId.systemDefault());
  }
}
