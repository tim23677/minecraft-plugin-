package dev.orderhub.placeholder;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlaceholderCacheTest {

  private final AtomicLong clock = new AtomicLong(0);
  private final List<Runnable> queue = new ArrayList<>();

  private PlaceholderCache cache(int max, long ttl) {
    return new PlaceholderCache(max, ttl, clock::get, queue::add);
  }

  private void drain() {
    List<Runnable> copy = new ArrayList<>(queue);
    queue.clear();
    copy.forEach(Runnable::run);
  }

  @Test
  void firstAccessReturnsFallbackAndLoadsAsynchronously() {
    PlaceholderCache c = cache(10, 1000);
    AtomicInteger loads = new AtomicInteger();
    assertThat(c.get("k", () -> "v" + loads.incrementAndGet(), "-")).isEqualTo("-");
    assertThat(loads.get()).isZero(); // nichts synchron geladen
    drain();
    assertThat(c.get("k", () -> "x", "-")).isEqualTo("v1");
  }

  @Test
  void staleValueIsServedWhileRefreshing() {
    PlaceholderCache c = cache(10, 1000);
    c.get("k", () -> "old", "-");
    drain();
    clock.set(5000);
    assertThat(c.get("k", () -> "new", "-")).isEqualTo("old");
    drain();
    assertThat(c.get("k", () -> "x", "-")).isEqualTo("new");
  }

  @Test
  void concurrentRefreshesAreDeduplicated() {
    PlaceholderCache c = cache(10, 1000);
    c.get("k", () -> "v", "-");
    c.get("k", () -> "v", "-");
    c.get("k", () -> "v", "-");
    assertThat(queue).hasSize(1);
  }

  @Test
  void sizeIsBounded() {
    PlaceholderCache c = cache(3, 1000);
    for (int i = 0; i < 10; i++) {
      c.get("k" + i, () -> "v", "-");
    }
    drain();
    assertThat(c.size()).isLessThanOrEqualTo(3);
  }

  @Test
  void loaderFailureKeepsFallbackAndAllowsRetry() {
    PlaceholderCache c = cache(10, 1000);
    c.get(
        "k",
        () -> {
          throw new IllegalStateException("db down");
        },
        "-");
    drain();
    assertThat(c.get("k", () -> "ok", "-")).isEqualTo("-");
    drain();
    assertThat(c.get("k", () -> "x", "-")).isEqualTo("ok");
  }
}
