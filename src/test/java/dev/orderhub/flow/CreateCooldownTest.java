package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CreateCooldownTest {

  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();

  @Test
  void secondCreateWithinTheWindowIsRefusedWithTheRemainingTime() {
    AtomicLong clock = new AtomicLong(10_000);
    CreateCooldown cooldown = new CreateCooldown(clock::get);
    assertThat(cooldown.tryAcquire(A, 5)).isZero();
    clock.addAndGet(2_000);
    assertThat(cooldown.tryAcquire(A, 5)).isEqualTo(3_000);
    clock.addAndGet(3_000);
    assertThat(cooldown.tryAcquire(A, 5)).isZero();
  }

  @Test
  void playersAreIndependentAndZeroDisablesTheLimit() {
    AtomicLong clock = new AtomicLong(0);
    CreateCooldown cooldown = new CreateCooldown(clock::get);
    assertThat(cooldown.tryAcquire(A, 5)).isZero();
    assertThat(cooldown.tryAcquire(B, 5)).isZero();
    assertThat(cooldown.tryAcquire(A, 0)).isZero();
    assertThat(cooldown.tryAcquire(A, 0)).isZero();
  }

  @Test
  void forgettingAPlayerResetsTheWindow() {
    AtomicLong clock = new AtomicLong(0);
    CreateCooldown cooldown = new CreateCooldown(clock::get);
    cooldown.tryAcquire(A, 60);
    cooldown.forget(A);
    assertThat(cooldown.tryAcquire(A, 60)).isZero();
  }
}
