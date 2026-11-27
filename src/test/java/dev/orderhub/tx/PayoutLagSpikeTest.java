package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.economy.GlobalThreadEconomy;
import dev.orderhub.scheduler.PluginScheduler;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.Invariants;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Flow-Test "Lag-Spike": Der globale Thread haengt, waehrend 20 Auszahlungen warten. Keine darf
 * nach MANUAL_REVIEW eskalieren, weil kein Task je gestartet wurde. Nach dem Lag werden alle genau
 * einmal ausgezahlt.
 */
class PayoutLagSpikeTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final long MONEY = 1_000_000;
  private static final int PAYOUTS = 20;

  private final ExecutorService globalThread =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-global");
        t.setDaemon(true);
        return t;
      });

  @AfterEach
  void shutdown() {
    globalThread.shutdownNow();
  }

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

  @Test
  void lagSpikeWithTwentyWaitingPayoutsEscalatesNoneAndPaysAllAfterwards() throws Exception {
    Harness h = new Harness(Fees.none(), Limits.defaults());
    h.economy.setBalance(OWNER, MONEY);
    for (int i = 0; i < PAYOUTS; i++) {
      long orderId = h.create(OWNER, 100, 5, 1).orderId();
      h.deliver(orderId, SUPPLIER, 1);
    }
    assertThat(h.storage.snapshot().payouts()).hasSize(PAYOUTS);

    GlobalThreadEconomy lagged =
        new GlobalThreadEconomy(h.economy, scheduler, () -> false, Duration.ofMillis(50));
    PayoutWorker worker =
        new PayoutWorker(
            h.storage, lagged, h.clock, 3, Duration.ofSeconds(1), Duration.ofSeconds(8));

    CountDownLatch lagEnds = new CountDownLatch(1);
    CountDownLatch lagStarted = new CountDownLatch(1);
    globalThread.execute(() -> {
      lagStarted.countDown();
      try {
        lagEnds.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    lagStarted.await();

    // Weit mehr Laeufe als maxAttempts: ohne den Fix waere jede Zeile nach 3 Versuchen im Review.
    for (int round = 0; round < 40; round++) {
      worker.runOnce(100);
      h.clock.advance(Duration.ofMinutes(10).toMillis());
    }
    assertThat(h.storage.snapshot().payouts())
        .allMatch(p -> p.state() == PayoutRepository.State.PENDING);
    assertThat(h.economy.balance(SUPPLIER)).isZero();

    lagEnds.countDown();
    globalThread.submit(() -> {}).get();

    for (int round = 0; round < PAYOUTS + 5; round++) {
      worker.runOnce(100);
      h.clock.advance(Duration.ofMinutes(10).toMillis());
    }
    assertThat(h.storage.snapshot().payouts())
        .allMatch(p -> p.state() == PayoutRepository.State.COMPLETED);
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(PAYOUTS * 100L);
    assertThat(h.economy.duplicateReferences()).isEmpty();
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }
}
