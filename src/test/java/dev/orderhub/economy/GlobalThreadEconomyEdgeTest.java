package dev.orderhub.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.scheduler.PluginScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Randfaelle der Geld-Bruecke: nie eingeplant, direkter Aufruf, Platzhalter ohne Provider. */
class GlobalThreadEconomyEdgeTest {

  private static final UUID P = UUID.randomUUID();

  private static PluginScheduler scheduler(java.util.function.Consumer<Runnable> onRunGlobal) {
    return (PluginScheduler)
        Proxy.newProxyInstance(
            PluginScheduler.class.getClassLoader(),
            new Class<?>[] {PluginScheduler.class},
            (proxy, method, args) -> {
              if (method.getName().equals("runGlobal")) {
                onRunGlobal.accept((Runnable) args[0]);
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static EconomyBridge counting(AtomicInteger calls, EconomyResult result) {
    return new EconomyBridge() {
      @Override
      public EconomyResult has(UUID player, long minor) {
        calls.incrementAndGet();
        return result;
      }

      @Override
      public EconomyResult withdraw(UUID player, long minor, String reference) {
        calls.incrementAndGet();
        return result;
      }

      @Override
      public EconomyResult deposit(UUID player, long minor, String reference) {
        calls.incrementAndGet();
        return result;
      }
    };
  }

  @Test
  void ifTheTaskCanNotBeScheduledNoMoneyMovesAndTheCallerMayRetry() {
    AtomicInteger calls = new AtomicInteger();
    PluginScheduler broken =
        scheduler(task -> {
          throw new IllegalStateException("plugin disabled");
        });
    GlobalThreadEconomy eco =
        new GlobalThreadEconomy(
            counting(calls, EconomyResult.OK), broken, () -> false, Duration.ofSeconds(1));
    assertThat(eco.has(P, 1)).isEqualTo(EconomyResult.UNAVAILABLE);
    assertThat(eco.withdraw(P, 1, "tx")).isEqualTo(EconomyResult.UNAVAILABLE);
    assertThat(eco.deposit(P, 1, "tx")).isEqualTo(EconomyResult.UNAVAILABLE);
    assertThat(calls).hasValue(0);
  }

  @Test
  void onTheGlobalThreadTheProviderIsCalledDirectlyWithoutSchedulingOrLatency() {
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger scheduled = new AtomicInteger();
    GlobalThreadEconomy eco =
        new GlobalThreadEconomy(
            counting(calls, EconomyResult.INSUFFICIENT),
            scheduler(task -> scheduled.incrementAndGet()),
            () -> true,
            Duration.ofSeconds(1));
    assertThat(eco.has(P, 1)).isEqualTo(EconomyResult.INSUFFICIENT);
    assertThat(eco.withdraw(P, 1, "tx")).isEqualTo(EconomyResult.INSUFFICIENT);
    assertThat(eco.deposit(P, 1, "tx")).isEqualTo(EconomyResult.INSUFFICIENT);
    assertThat(calls).hasValue(3);
    assertThat(scheduled).hasValue(0);
    assertThat(eco.latency().snapshot().samples()).isZero();
  }

  @Test
  void everyProviderResultIsHandedBackUnchanged() {
    for (EconomyResult expected : EconomyResult.values()) {
      GlobalThreadEconomy eco =
          new GlobalThreadEconomy(
              counting(new AtomicInteger(), expected),
              scheduler(Runnable::run),
              () -> false,
              Duration.ofSeconds(1));
      assertThat(eco.withdraw(P, 5, "tx")).isEqualTo(expected);
      assertThat(eco.deposit(P, 5, "tx")).isEqualTo(expected);
      assertThat(eco.has(P, 5)).isEqualTo(expected);
    }
  }

  @Test
  void aRuntimeExceptionOfTheProviderIsRethrownAsIsForReadsToo() {
    EconomyBridge boom =
        new EconomyBridge() {
          @Override
          public EconomyResult has(UUID player, long minor) {
            throw new IllegalArgumentException("bad player");
          }

          @Override
          public EconomyResult withdraw(UUID player, long minor, String reference) {
            return EconomyResult.OK;
          }

          @Override
          public EconomyResult deposit(UUID player, long minor, String reference) {
            return EconomyResult.OK;
          }
        };
    GlobalThreadEconomy eco =
        new GlobalThreadEconomy(boom, scheduler(Runnable::run), () -> false, Duration.ofSeconds(1));
    assertThatThrownBy(() -> eco.has(P, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("bad player");
  }

  @Test
  void aProviderErrorOnWithdrawOrDepositIsAnUnknownOutcomeWithTheCauseKept() {
    // Ein Error (kein RuntimeException) im Provider: ob das Geld floss, ist unbekannt. Nie blind
    // wiederholen, nie als "kein Geld geflossen" melden.
    AssertionError cause = new AssertionError("provider died");
    EconomyBridge dying =
        new EconomyBridge() {
          @Override
          public EconomyResult has(UUID player, long minor) {
            return EconomyResult.OK;
          }

          @Override
          public EconomyResult withdraw(UUID player, long minor, String reference) {
            throw cause;
          }

          @Override
          public EconomyResult deposit(UUID player, long minor, String reference) {
            throw cause;
          }
        };
    GlobalThreadEconomy eco =
        new GlobalThreadEconomy(
            dying, scheduler(Runnable::run), () -> false, Duration.ofSeconds(1));
    assertThatThrownBy(() -> eco.withdraw(P, 5, "ref-1"))
        .isInstanceOf(EconomyOutcomeUnknownException.class)
        .hasMessageContaining("ref-1")
        .hasCause(cause);
    assertThatThrownBy(() -> eco.deposit(P, 5, "ref-2"))
        .isInstanceOf(EconomyOutcomeUnknownException.class)
        .hasMessageContaining("ref-2")
        .hasCause(cause);
  }

  @Test
  void theUnavailablePlaceholderNeverReportsSuccess() {
    EconomyBridge none = new UnavailableEconomy();
    assertThat(none.has(P, 0)).isEqualTo(EconomyResult.UNAVAILABLE);
    assertThat(none.withdraw(P, 1, "tx")).isEqualTo(EconomyResult.UNAVAILABLE);
    assertThat(none.deposit(P, 1, "tx")).isEqualTo(EconomyResult.UNAVAILABLE);
  }
}
