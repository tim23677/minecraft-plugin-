package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SearchThrottleTest {

  private AtomicLong clock;
  private SearchThrottle throttle;
  private UUID a;
  private UUID b;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    clock = new AtomicLong(1_000);
    throttle = new SearchThrottle(clock::get);
    a = UUID.randomUUID();
    b = UUID.randomUUID();
  }

  @Test
  void allowsTheLimitAndRefusesTheNextSearchInsideTheWindow() {
    for (int i = 0; i < SearchThrottle.LIMIT; i++) {
      assertThat(throttle.tryAcquire(a)).isTrue();
    }
    assertThat(throttle.tryAcquire(a)).isFalse();
  }

  @Test
  void aRefusedSearchDoesNotUseUpASlot() {
    for (int i = 0; i < SearchThrottle.LIMIT; i++) {
      throttle.tryAcquire(a);
    }
    clock.addAndGet(SearchThrottle.WINDOW_MILLIS - 1);
    assertThat(throttle.tryAcquire(a)).isFalse();
    clock.addAndGet(1);
    assertThat(throttle.tryAcquire(a)).isTrue();
  }

  @Test
  void playersAreCountedSeparately() {
    for (int i = 0; i < SearchThrottle.LIMIT; i++) {
      throttle.tryAcquire(a);
    }
    assertThat(throttle.tryAcquire(b)).isTrue();
  }

  @Test
  void purgeRemovesOnlyPlayersWhoseWindowHasPassed() {
    throttle.tryAcquire(a);
    clock.addAndGet(SearchThrottle.WINDOW_MILLIS);
    throttle.tryAcquire(b);

    assertThat(throttle.purgeExpired()).isEqualTo(1);
    assertThat(throttle.size()).isEqualTo(1);
  }

  @Test
  void forgetFreesThePlayer() {
    for (int i = 0; i < SearchThrottle.LIMIT; i++) {
      throttle.tryAcquire(a);
    }
    throttle.forget(a);
    assertThat(throttle.tryAcquire(a)).isTrue();
  }
}
