package dev.orderhub.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.scheduler.PluginScheduler;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GlobalThreadEconomyTest {

  private static final UUID P = UUID.randomUUID();

  private final ExecutorService globalThread =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-global");
        t.setDaemon(true);
        return t;
      });
  private final List<String> threadsSeen = new CopyOnWriteArrayList<>();

  @AfterEach
  void shutdown() {
    globalThread.shutdownNow();
  }

  private final EconomyBridge recording =
      new EconomyBridge() {
        @Override
        public EconomyResult has(UUID player, long minor) {
          threadsSeen.add(Thread.currentThread().getName());
          return EconomyResult.OK;
        }

        @Override
        public EconomyResult withdraw(UUID player, long minor, String reference) {
          threadsSeen.add(Thread.currentThread().getName());
          return EconomyResult.OK;
        }

        @Override
        public EconomyResult deposit(UUID player, long minor, String reference) {
          threadsSeen.add(Thread.currentThread().getName());
          return EconomyResult.OK;
        }
      };

  /** Scheduler, dessen runGlobal auf dem einzigen "globalen" Thread laeuft. */
  private final PluginScheduler scheduler =
      new PluginScheduler() {
        @Override
        public void runGlobal(Runnable task) {
          globalThread.execute(task);
        }

        @Override
        public void runAt(Location location, Runnable task) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean runOn(Entity entity, Runnable task) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void runLater(Runnable task, long delayTicks) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void runTimer(Runnable task, long initialDelayTicks, long periodTicks) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void runAsync(Runnable task) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void cancelAll() {}
      };

  private GlobalThreadEconomy wrap(Duration timeout) {
    return new GlobalThreadEconomy(
        recording,
        scheduler,
        () -> "fake-global".equals(Thread.currentThread().getName()),
        timeout);
  }

  @Test
  void noProviderCallHappensOutsideTheGlobalThread() throws Exception {
    GlobalThreadEconomy eco = wrap(Duration.ofSeconds(5));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                assertThat(eco.has(P, 100)).isEqualTo(EconomyResult.OK);
                assertThat(eco.withdraw(P, 100, "tx1")).isEqualTo(EconomyResult.OK);
                assertThat(eco.deposit(P, 100, "tx2")).isEqualTo(EconomyResult.OK);
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "orderhub-worker");
    worker.start();
    worker.join();
    assertThat(failure.get()).isNull();
    assertThat(threadsSeen).hasSize(3).containsOnly("fake-global");
  }

  @Test
  void callingFromTheGlobalThreadDoesNotDeadlock() throws Exception {
    GlobalThreadEconomy eco = wrap(Duration.ofSeconds(5));
    AtomicReference<EconomyResult> result = new AtomicReference<>();
    globalThread.submit(() -> result.set(eco.withdraw(P, 5, "tx"))).get();
    assertThat(result.get()).isEqualTo(EconomyResult.OK);
  }

  @Test
  void neverStartedTimeoutOnWithdrawAndDepositIsUnavailable() throws Exception {
    AtomicBoolean release = new AtomicBoolean();
    globalThread.execute(() -> {
      while (!release.get()) {
        Thread.onSpinWait();
      }
    });
    GlobalThreadEconomy eco = wrap(Duration.ofMillis(100));
    // Der Task wurde nie gestartet: sicher ist kein Geld geflossen, der Aufrufer darf wiederholen.
    assertThat(eco.withdraw(P, 5, "tx")).isEqualTo(EconomyResult.UNAVAILABLE);
    assertThat(eco.deposit(P, 5, "tx")).isEqualTo(EconomyResult.UNAVAILABLE);
    release.set(true);
    globalThread.submit(() -> {}).get();
    // Abgebrochene Tasks haben den Provider nie aufgerufen.
    assertThat(threadsSeen).isEmpty();
  }

  @Test
  void startedButUnfinishedCallOnWithdrawAndDepositIsAnUnknownOutcome() throws Exception {
    CountDownLatch inside = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    EconomyBridge blocking =
        new EconomyBridge() {
          @Override
          public EconomyResult has(UUID player, long minor) {
            return EconomyResult.OK;
          }

          @Override
          public EconomyResult withdraw(UUID player, long minor, String reference) {
            return block();
          }

          @Override
          public EconomyResult deposit(UUID player, long minor, String reference) {
            return block();
          }

          private EconomyResult block() {
            inside.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return EconomyResult.OK;
          }
        };
    ExecutorService twoThreads = Executors.newFixedThreadPool(2);
    PluginScheduler parallel =
        new PluginScheduler() {
          @Override
          public void runGlobal(Runnable task) {
            twoThreads.execute(task);
          }

          @Override
          public void runAt(Location location, Runnable task) {
            throw new UnsupportedOperationException();
          }

          @Override
          public boolean runOn(Entity entity, Runnable task) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void runLater(Runnable task, long delayTicks) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void runTimer(Runnable task, long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void runAsync(Runnable task) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void cancelAll() {}
        };
    try {
      GlobalThreadEconomy eco =
          new GlobalThreadEconomy(blocking, parallel, () -> false, Duration.ofMillis(300));
      ExecutorService callers = Executors.newFixedThreadPool(2);
      try {
        var w = callers.submit(() -> eco.withdraw(P, 5, "tx"));
        var d = callers.submit(() -> eco.deposit(P, 5, "tx"));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> w.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(EconomyOutcomeUnknownException.class);
        assertThatThrownBy(() -> d.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(EconomyOutcomeUnknownException.class);
      } finally {
        release.countDown();
        callers.shutdownNow();
      }
    } finally {
      twoThreads.shutdownNow();
    }
  }

  @Test
  void timeoutOnHasIsUnavailable() {
    AtomicBoolean release = new AtomicBoolean();
    globalThread.execute(() -> {
      while (!release.get()) {
        Thread.onSpinWait();
      }
    });
    GlobalThreadEconomy eco = wrap(Duration.ofMillis(100));
    assertThat(eco.has(P, 5)).isEqualTo(EconomyResult.UNAVAILABLE);
    release.set(true);
  }

  @Test
  void providerExceptionPropagatesAsUnknownOutcome() {
    EconomyBridge throwing =
        new EconomyBridge() {
          @Override
          public EconomyResult has(UUID player, long minor) {
            return EconomyResult.OK;
          }

          @Override
          public EconomyResult withdraw(UUID player, long minor, String reference) {
            throw new EconomyOutcomeUnknownException("boom", null);
          }

          @Override
          public EconomyResult deposit(UUID player, long minor, String reference) {
            return EconomyResult.OK;
          }
        };
    GlobalThreadEconomy eco =
        new GlobalThreadEconomy(throwing, scheduler, () -> false, Duration.ofSeconds(5));
    assertThatThrownBy(() -> eco.withdraw(P, 5, "tx"))
        .isInstanceOf(EconomyOutcomeUnknownException.class);
  }

  @Test
  void callsAndTimeoutsAreMeasuredForTheHealthReport() throws Exception {
    GlobalThreadEconomy eco = wrap(Duration.ofSeconds(5));
    assertThat(eco.latency().snapshot().samples()).isZero();
    Thread worker = new Thread(() -> eco.withdraw(P, 5, "tx"), "orderhub-worker");
    worker.start();
    worker.join();
    assertThat(eco.latency().snapshot().samples()).isEqualTo(1);

    AtomicBoolean release = new AtomicBoolean();
    globalThread.execute(() -> {
      while (!release.get()) {
        Thread.onSpinWait();
      }
    });
    GlobalThreadEconomy lagging = wrap(Duration.ofMillis(120));
    assertThat(lagging.has(P, 1)).isEqualTo(EconomyResult.UNAVAILABLE);
    release.set(true);
    assertThat(lagging.latency().snapshot().maxMillis()).isGreaterThanOrEqualTo(120);
  }
}
