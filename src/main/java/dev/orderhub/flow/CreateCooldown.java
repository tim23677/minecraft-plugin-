package dev.orderhub.flow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Rate Limit fuer die Auftragserstellung: Mindestabstand je Spieler, nur im Speicher. */
final class CreateCooldown {

  private final LongSupplier clock;
  private final Map<UUID, Long> last = new ConcurrentHashMap<>();

  CreateCooldown(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * Beansprucht den naechsten Slot atomar.
   *
   * @return 0, wenn der Spieler jetzt erstellen darf (Slot ist vergeben), sonst die verbleibende
   *     Wartezeit in Millisekunden
   */
  long tryAcquire(UUID player, int cooldownSeconds) {
    if (cooldownSeconds <= 0) {
      return 0;
    }
    long now = clock.getAsLong();
    long window = cooldownSeconds * 1000L;
    long[] remaining = {0};
    last.compute(
        player,
        (id, previous) -> {
          if (previous != null && now - previous < window) {
            remaining[0] = window - (now - previous);
            return previous;
          }
          return now;
        });
    return remaining[0];
  }

  /**
   * Entfernt Eintraege, deren Fenster abgelaufen ist (zeitgesteuert aufgerufen), damit die Tabelle
   * nicht mit der Zahl der Spieler waechst, die je erstellt haben.
   *
   * @return Anzahl entfernter Eintraege
   */
  int purgeExpired(int cooldownSeconds) {
    long now = clock.getAsLong();
    long window = Math.max(0, cooldownSeconds) * 1000L;
    int before = last.size();
    last.values().removeIf(previous -> now - previous >= window);
    return before - last.size();
  }

  int size() {
    return last.size();
  }

  /** Gibt Speicher frei, wenn der Spieler geht. */
  void forget(UUID player) {
    last.remove(player);
  }
}
