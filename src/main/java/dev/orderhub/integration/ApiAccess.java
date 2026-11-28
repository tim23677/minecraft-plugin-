package dev.orderhub.integration;

import dev.orderhub.config.Limits;
import java.util.function.Predicate;

/**
 * Rechte- und Limitentscheidung fuer {@code createOrder} der API. Gleiche Regeln wie der Befehl:
 * {@code orderhub.create}, Limit aus {@code orderhub.limit.*}, {@code orderhub.bypass.fee}.
 */
public record ApiAccess(boolean allowed, int maxOpenOrders, boolean feeExempt) {

  /** Auftraggeber nicht pruefbar (offline) und kein Bypass: abgelehnt. */
  public static final ApiAccess DENIED = new ApiAccess(false, 0, false);

  /** Entscheidung fuer einen online befindlichen Spieler. */
  public static ApiAccess forPlayer(Predicate<String> hasPermission, Limits limits) {
    if (!hasPermission.test("orderhub.create")) {
      return DENIED;
    }
    return new ApiAccess(
        true, limits.maxOpenOrders(hasPermission), hasPermission.test("orderhub.bypass.fee"));
  }

  /** Ausdruecklicher Bypass: Standardlimit, volle Gebuehr. */
  public static ApiAccess bypass(Limits limits) {
    return new ApiAccess(true, limits.defaultMaxOpenOrders(), false);
  }
}
