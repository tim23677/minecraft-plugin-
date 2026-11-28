package dev.orderhub.flow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Mindestabstand zwischen zwei Abfragen eines Spielers (Markt, Statistik, Verlauf und so weiter).
 * Jede dieser Abfragen kostet eine Datenbankabfrage, ein Spieler mit einem Makro soll sie nicht im
 * Sekundentakt dutzendfach ausloesen koennen.
 *
 * @since 2.0.0
 */
public final class QueryThrottle {

  private static final int PURGE_ABOVE = 1_024;

  private final LongSupplier clock;
  private final Map<UUID, Long> last = new ConcurrentHashMap<>();

  public QueryThrottle(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * Beansprucht den Platz atomar.
   *
   * @param intervalMs Mindestabstand, 0 oder weniger schaltet die Begrenzung aus
   * @return {@code true}, wenn die Abfrage jetzt laufen darf
   */
  public boolean tryAcquire(UUID player, long intervalMs) {
    if (intervalMs <= 0) {
      return true;
    }
    long now = clock.getAsLong();
    boolean[] allowed = {false};
    last.compute(
        player,
        (id, previous) -> {
          if (previous == null || now - previous >= intervalMs) {
            allowed[0] = true;
            return now;
          }
          return previous;
        });
    if (last.size() > PURGE_ABOVE) {
      last.values().removeIf(previous -> now - previous >= intervalMs);
    }
    return allowed[0];
  }

  public void forget(UUID player) {
    last.remove(player);
  }

  int size() {
    return last.size();
  }
}
