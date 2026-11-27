package dev.orderhub.flow;

import dev.orderhub.storage.InsightsRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * Fasst die Hinweise zusammen, die sich seit dem letzten Besuch angesammelt haben. Rein
 * rechnerisch, ohne Bukkit.
 *
 * @param deliveredItems gelieferte Items an eigene Auftraege
 * @param deliveries Anzahl der Lieferungen
 * @param expiredOrders abgelaufene Auftraege
 * @param alerts Preisalarme (Item, Menge, Stueckpreis, Auftrags-Id)
 * @since 2.0.0
 */
public record LoginSummary(
    long deliveredItems, int deliveries, int expiredOrders, List<String[]> alerts) {

  /** Kleine Summe: Alarme werden einzeln gezeigt, mehr als diese Zahl nur als Anzahl. */
  public static final int MAX_ALERTS_SHOWN = 3;

  public static LoginSummary of(List<InsightsRepository.Note> notes) {
    long items = 0;
    int deliveries = 0;
    int expired = 0;
    List<String[]> alerts = new ArrayList<>();
    for (InsightsRepository.Note note : notes) {
      String[] parts = note.payload().split("\\|");
      switch (note.kind()) {
        case "delivered" -> {
          deliveries++;
          if (parts.length >= 2) {
            try {
              items += Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
              // beschaedigter Hinweis: zaehlt als Lieferung ohne Menge
            }
          }
        }
        case "expired" -> expired++;
        case "price-alert" -> {
          if (parts.length == 4) {
            alerts.add(parts);
          }
        }
        default -> {
          // unbekannte Art (spaetere Version): wird ignoriert, nicht angezeigt
        }
      }
    }
    return new LoginSummary(items, deliveries, expired, List.copyOf(alerts));
  }

  public boolean isEmpty() {
    return deliveries == 0 && expiredOrders == 0 && alerts.isEmpty();
  }
}
