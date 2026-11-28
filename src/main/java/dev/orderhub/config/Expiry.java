package dev.orderhub.config;

import java.time.Duration;

/**
 * Laufzeit von Auftraegen.
 *
 * @param defaultLifetime Standard-Laufzeit
 * @param autoRenewEnabled ob Auto-Verlaengerung global erlaubt ist
 */
public record Expiry(Duration defaultLifetime, boolean autoRenewEnabled) {

  public Expiry {
    if (defaultLifetime == null || defaultLifetime.isNegative() || defaultLifetime.isZero()) {
      throw new IllegalArgumentException("defaultLifetime");
    }
  }

  public static Expiry defaults() {
    return new Expiry(Duration.ofDays(7), true);
  }
}
