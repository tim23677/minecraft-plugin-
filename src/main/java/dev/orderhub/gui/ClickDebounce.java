package dev.orderhub.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Entprellt Button-Klicks je Spieler. Thread-sicher. */
final class ClickDebounce {

  private final long windowNanos;
  private final LongSupplier nanoClock;
  private final Map<UUID, Long> lastAccepted = new ConcurrentHashMap<>();

  ClickDebounce(long windowMillis, LongSupplier nanoClock) {
    this.windowNanos = windowMillis * 1_000_000L;
    this.nanoClock = nanoClock;
  }

  /** {@code true}, wenn der Klick zaehlt; {@code false}, wenn er zu kurz nach dem letzten kam. */
  boolean tryAcquire(UUID playerId) {
    if (windowNanos == 0) {
      return true;
    }
    long now = nanoClock.getAsLong();
    boolean[] accepted = new boolean[1];
    lastAccepted.compute(
        playerId,
        (id, last) -> {
          if (last != null && now - last < windowNanos) {
            return last;
          }
          accepted[0] = true;
          return now;
        });
    return accepted[0];
  }

  void forget(UUID playerId) {
    lastAccepted.remove(playerId);
  }
}
