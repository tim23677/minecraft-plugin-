package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryThrottleTest {

  private AtomicLong clock;
  private QueryThrottle throttle;
  private UUID a;
  private UUID b;

  @BeforeEach
  void setUp() {
    clock = new AtomicLong(10_000);
    throttle = new QueryThrottle(clock::get);
    a = UUID.randomUUID();
    b = UUID.randomUUID();
  }

  @Test
  void theSecondRequestInsideTheIntervalIsRefused() {
    assertThat(throttle.tryAcquire(a, 250)).isTrue();
    clock.addAndGet(249);
    assertThat(throttle.tryAcquire(a, 250)).isFalse();
    clock.addAndGet(1);
    assertThat(throttle.tryAcquire(a, 250)).isTrue();
  }

  @Test
  void aRefusedRequestDoesNotPushTheWindowBack() {
    throttle.tryAcquire(a, 250);
    clock.addAndGet(200);
    assertThat(throttle.tryAcquire(a, 250)).isFalse();
    clock.addAndGet(50);
    assertThat(throttle.tryAcquire(a, 250)).isTrue();
  }

  @Test
  void playersAreIndependentAndZeroTurnsItOff() {
    assertThat(throttle.tryAcquire(a, 250)).isTrue();
    assertThat(throttle.tryAcquire(b, 250)).isTrue();
    assertThat(throttle.tryAcquire(a, 0)).isTrue();
    assertThat(throttle.tryAcquire(a, -5)).isTrue();
  }

  @Test
  void forgetFreesThePlayer() {
    throttle.tryAcquire(a, 250);
    throttle.forget(a);
    assertThat(throttle.tryAcquire(a, 250)).isTrue();
    assertThat(throttle.size()).isEqualTo(1);
  }
}
