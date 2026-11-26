package dev.orderhub.placeholder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Kleiner begrenzter Cache fuer Platzhalterwerte. Antwortet nie mit einer Datenbankabfrage: Fehlt
 * ein Wert oder ist er alt, wird er asynchron nachgeladen und bis dahin der alte Wert oder der
 * Fallback geliefert.
 */
public final class PlaceholderCache {

  private record Entry(String value, long loadedAt) {}

  /** LRU-Map mit fester Obergrenze. */
  private static final class LruMap extends LinkedHashMap<String, Entry> {
    private static final long serialVersionUID = 1L;
    private final int max;

    LruMap(int max) {
      super(16, 0.75f, true);
      this.max = max;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
      return size() > max;
    }
  }

  private final long ttlMillis;
  private final LongSupplier clock;
  private final Consumer<Runnable> async;
  private final Map<String, Entry> entries;
  private final Set<String> loading = ConcurrentHashMap.newKeySet();

  public PlaceholderCache(
      int maxEntries, long ttlMillis, LongSupplier clock, Consumer<Runnable> asyncExecutor) {
    this.ttlMillis = ttlMillis;
    this.clock = clock;
    this.async = asyncExecutor;
    this.entries = java.util.Collections.synchronizedMap(new LruMap(maxEntries));
  }

  /** Liefert den gecachten Wert oder {@code fallback}. Stoesst bei Bedarf ein Nachladen an. */
  public String get(String key, Supplier<String> loader, String fallback) {
    Entry entry = entries.get(key);
    long now = clock.getAsLong();
    if (entry == null || now - entry.loadedAt() > ttlMillis) {
      refresh(key, loader);
    }
    return entry == null ? fallback : entry.value();
  }

  private void refresh(String key, Supplier<String> loader) {
    if (!loading.add(key)) {
      return;
    }
    try {
      async.accept(() -> {
        try {
          entries.put(key, new Entry(loader.get(), clock.getAsLong()));
        } catch (RuntimeException e) {
          // Alter Wert bleibt bestehen, naechster Zugriff versucht es erneut.
        } finally {
          loading.remove(key);
        }
      });
    } catch (RuntimeException e) {
      // Scheduler nimmt nichts mehr an (Plugin wird deaktiviert): Fallback bleibt, Schluessel frei.
      loading.remove(key);
    }
  }

  public int size() {
    return entries.size();
  }
}
