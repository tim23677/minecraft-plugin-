package dev.orderhub.flow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Begrenzt Textsuchen im Marktplatz je Spieler: hoechstens {@code limit} Suchen innerhalb des
 * Fensters. Eine Textsuche ist eine LIKE-Abfrage und teurer als eine Seite ohne Text.
 *
 * @since 2.0.0
 */
final class SearchThrottle {

  static final int LIMIT = 3;
  static final long WINDOW_MILLIS = 5_000;
  private static final int PURGE_ABOVE = 1_024;

  private final LongSupplier clock;
  private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();

  SearchThrottle(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * @return {@code true}, wenn die Suche jetzt laufen darf (der Platz ist dann vergeben)
   */
  boolean tryAcquire(UUID player) {
    long now = clock.getAsLong();
    boolean[] allowed = {false};
    recent.compute(
        player,
        (id, queue) -> {
          Deque<Long> q = queue == null ? new ArrayDeque<>() : queue;
          while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MILLIS) {
            q.pollFirst();
          }
          if (q.size() < LIMIT) {
            q.addLast(now);
            allowed[0] = true;
          }
          return q;
        });
    if (recent.size() > PURGE_ABOVE) {
      purgeExpired();
    }
    return allowed[0];
  }

  /** Entfernt Spieler, deren letzte Suche ausserhalb des Fensters liegt. */
  int purgeExpired() {
    long now = clock.getAsLong();
    int before = recent.size();
    recent.values().removeIf(q -> q.isEmpty() || now - q.peekLast() >= WINDOW_MILLIS);
    return before - recent.size();
  }

  void forget(UUID player) {
    recent.remove(player);
  }

  int size() {
    return recent.size();
  }
}
