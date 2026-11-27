package dev.orderhub.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Prueft die Gesundheitswerte gegen Grenzwerte und liefert Warnungen fuer das Log. Eine Warnung
 * wird nur beim Wechsel von "in Ordnung" zu "ueberschritten" gemeldet, nicht bei jeder Pruefung,
 * damit das Log nicht volllaeuft. Thread-sicher.
 *
 * @since 2.0.0
 */
public final class HealthMonitor {

  /**
   * Grenzwerte. 0 schaltet die jeweilige Pruefung ab.
   *
   * @param economyLatencyMillis Median der Economy-Latenz
   * @param openPayments offene Zahlungen
   * @param oldestPaymentMinutes Alter der aeltesten offenen Zahlung
   * @param manualReview Eintraege in der manuellen Pruefung
   */
  public record Thresholds(
      long economyLatencyMillis, int openPayments, long oldestPaymentMinutes, int manualReview) {}

  private final java.util.Set<String> active = new java.util.HashSet<>();

  /**
   * @return Warnungen, die neu hinzugekommen sind.
   */
  public synchronized List<String> check(AdminService.Health health, Thresholds t) {
    List<String> fresh = new ArrayList<>();
    evaluate(
        fresh,
        "economy-latency",
        t.economyLatencyMillis() > 0 && health.economy().medianMillis() >= t.economyLatencyMillis(),
        "Economy median latency is "
            + health.economy().medianMillis()
            + " ms (limit "
            + t.economyLatencyMillis()
            + " ms)");
    evaluate(
        fresh,
        "open-payments",
        t.openPayments() > 0 && health.openPayments() >= t.openPayments(),
        health.openPayments() + " payments are waiting (limit " + t.openPayments() + ")");
    long ageMinutes = health.oldestOpenPaymentAgeMillis() / 60_000;
    evaluate(
        fresh,
        "oldest-payment",
        t.oldestPaymentMinutes() > 0 && ageMinutes >= t.oldestPaymentMinutes(),
        "The oldest open payment is "
            + ageMinutes
            + " minutes old (limit "
            + t.oldestPaymentMinutes()
            + ")");
    evaluate(
        fresh,
        "manual-review",
        t.manualReview() > 0 && health.manualReview() >= t.manualReview(),
        health.manualReview() + " transactions need manual review (/orderadmin review)");
    return fresh;
  }

  private void evaluate(List<String> fresh, String key, boolean exceeded, String message) {
    if (exceeded) {
      if (active.add(key)) {
        fresh.add(message);
      }
    } else {
      active.remove(key);
    }
  }
}
