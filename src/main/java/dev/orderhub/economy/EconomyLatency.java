package dev.orderhub.economy;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Misst, wie lange Economy-Aufrufe vom Einplanen bis zum Ergebnis dauern (Ringpuffer der letzten
 * Aufrufe). Thread-sicher und ohne Sperren, damit die Messung die Aufrufe nicht bremst.
 *
 * @since 2.0.0
 */
public final class EconomyLatency {

  /** Ausgewertete Messwerte in Millisekunden. */
  public record Snapshot(int samples, long medianMillis, long maxMillis, long lastMillis) {
    public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0);
  }

  private final AtomicLongArray ring;
  private final AtomicInteger next = new AtomicInteger();
  private final AtomicInteger count = new AtomicInteger();
  private volatile long last;

  public EconomyLatency(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity");
    }
    this.ring = new AtomicLongArray(capacity);
  }

  /** Schreibt einen Messwert (negative Werte werden als 0 gezaehlt). */
  public void record(long millis) {
    long value = Math.max(0, millis);
    int index = Math.floorMod(next.getAndIncrement(), ring.length());
    ring.set(index, value);
    count.updateAndGet(c -> Math.min(ring.length(), c + 1));
    last = value;
  }

  public Snapshot snapshot() {
    int n = Math.min(count.get(), ring.length());
    if (n == 0) {
      return Snapshot.EMPTY;
    }
    long[] values = new long[n];
    for (int i = 0; i < n; i++) {
      values[i] = ring.get(i);
    }
    Arrays.sort(values);
    return new Snapshot(n, values[n / 2], values[n - 1], last);
  }
}
