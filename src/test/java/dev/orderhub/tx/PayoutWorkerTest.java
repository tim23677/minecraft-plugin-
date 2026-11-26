package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryEconomy;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import dev.orderhub.testsupport.InMemoryStorage;
import dev.orderhub.testsupport.Invariants;
import dev.orderhub.testsupport.SimulatedCrash;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PayoutWorkerTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final long MONEY = 1_000_000;

  private static Harness harness() {
    Harness h = new Harness(Fees.none(), Limits.defaults());
    h.economy.setBalance(OWNER, MONEY);
    return h;
  }

  /** Auftrag mit einer festgeschriebenen Lieferung: genau eine Auszahlung (100) in der Queue. */
  private static UUID oneDelivery(Harness h) {
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(id, SUPPLIER, 1, UUID.randomUUID().toString());
    h.delivery.confirmItemsRemoved(p.journalId());
    h.delivery.commit(p.journalId());
    return p.journalId();
  }

  private static TxState state(Harness h, UUID journalId) {
    return h.storage.read(uow -> uow.journal().find(journalId).orElseThrow()).state();
  }

  private static PayoutRepository.Item row(Harness h) {
    return h.storage.snapshot().payouts().get(0);
  }

  @Test
  void paysOnceAndIsIdempotentOnRerun() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    var report = h.worker.runOnce(10);
    assertThat(report.completed()).isEqualTo(1);
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(100);
    assertThat(state(h, j)).isEqualTo(TxState.PAYOUT_COMPLETED);
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.COMPLETED);
    assertThat(h.worker.runOnce(10).completed()).isZero();
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void safeFailureIsReleasedWithBackoffAndRetried() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    h.economy.failNext(Op.DEPOSIT, Mode.RETURN_FAILED);
    var first = h.worker.runOnce(10);
    assertThat(first.released()).isEqualTo(1);
    assertThat(first.economyProblem()).isTrue();
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.PENDING);
    assertThat(row(h).attempts()).isEqualTo(1);
    assertThat(state(h, j)).isEqualTo(TxState.PAYOUT_PENDING);
    assertThat(h.economy.balance(SUPPLIER)).isZero();

    assertThat(h.worker.runOnce(10).skippedBackoff()).isTrue(); // Backoff aktiv
    assertThat(h.economy.count(Op.DEPOSIT)).isZero();
    h.clock.advance(Duration.ofSeconds(2).toMillis());
    assertThat(h.worker.runOnce(10).completed()).isEqualTo(1);
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(100);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void economyOutageEscalatesAfterBoundedAttempts() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    h.economy.setUnavailable(true);
    for (int i = 0; i < 3; i++) {
      h.worker.runOnce(10);
      h.clock.advance(Duration.ofMinutes(10).toMillis());
    }
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.REVIEW);
    assertThat(state(h, j)).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(h.economy.mutationCount()).isEqualTo(1); // nur die Abbuchung beim Erstellen
    h.economy.setUnavailable(false);
    h.worker.runOnce(10);
    assertThat(h.economy.balance(SUPPLIER)).isZero(); // wird nie automatisch nachgeholt
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void unknownOutcomeAfterDepositGoesToReviewAndIsNeverRepeated() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    h.economy.failNext(Op.DEPOSIT, Mode.THROW_AFTER);
    var report = h.worker.runOnce(10);
    assertThat(report.escalated()).isEqualTo(1);
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(100); // Zahlung ist tatsaechlich gelaufen
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.REVIEW);
    assertThat(state(h, j)).isEqualTo(TxState.MANUAL_REVIEW);
    h.clock.advance(Duration.ofHours(1).toMillis());
    h.worker.runOnce(10);
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void unknownOutcomeBeforeDepositIsAlsoTreatedConservatively() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    h.economy.failNext(Op.DEPOSIT, Mode.THROW_BEFORE);
    h.worker.runOnce(10);
    assertThat(state(h, j)).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(h.economy.count(Op.DEPOSIT)).isZero();
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void transientDbFailureWhileBookingSuccessIsRetried() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    h.storage.failTransaction(2, InMemoryStorage.FailAt.BEFORE_COMMIT); // 1 = Claim, 2 = Abschluss
    assertThat(h.worker.runOnce(10).completed()).isEqualTo(1);
    assertThat(state(h, j)).isEqualTo(TxState.PAYOUT_COMPLETED);
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
  }

  @Test
  void dbOutageAfterDepositLeavesTheRowToRecoveryAndNeverPaysTwice() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    for (int n = 2; n <= 4; n++) {
      h.storage.failTransaction(n, InMemoryStorage.FailAt.BEFORE_WORK);
    }
    assertThat(h.worker.runOnce(10).completed()).isZero();
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(100);
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.IN_PROGRESS);
    assertThat(state(h, j)).isEqualTo(TxState.PAYOUT_IN_PROGRESS);

    h.restart();
    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.payoutsEscalated()).isEqualTo(1);
    assertThat(state(h, j)).isEqualTo(TxState.MANUAL_REVIEW);
    h.drainPayouts();
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void crashDuringDepositIsEscalatedByRecovery() {
    Harness h = harness();
    UUID j = oneDelivery(h);
    h.economy.failNext(Op.DEPOSIT, Mode.CRASH_AFTER);
    assertThatThrownBy(() -> h.worker.runOnce(10)).isInstanceOf(SimulatedCrash.class);
    assertThat(state(h, j)).isEqualTo(TxState.PAYOUT_IN_PROGRESS);
    h.restart();
    h.recovery.runAtStartup();
    h.drainPayouts();
    assertThat(state(h, j)).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(100);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void refundsUseTheSameQueueAndTheSameRules() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var cancel = h.orders.cancelOrder(id, OWNER, false);
    assertThat(cancel.refunded()).isEqualTo(500);
    assertThat(h.worker.runOnce(10).completed()).isEqualTo(1);
    assertThat(h.economy.balance(OWNER)).isEqualTo(MONEY);
    assertThat(state(h, cancel.journalId())).isEqualTo(TxState.REFUND_COMPLETED);

    long id2 = h.create(OWNER, 100, 5, 1).orderId();
    var cancel2 = h.orders.cancelOrder(id2, OWNER, false);
    h.economy.failNext(Op.DEPOSIT, Mode.THROW_AFTER);
    h.worker.runOnce(10);
    assertThat(state(h, cancel2.journalId())).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void manyWorkersNeverPayTwice() throws Exception {
    Harness h = harness();
    long id = h.create(OWNER, 10, 40, 1).orderId();
    for (int i = 0; i < 40; i++) {
      var p = h.delivery.prepare(id, Harness.player(100 + i), 1, "n" + i);
      h.delivery.confirmItemsRemoved(p.journalId());
      h.delivery.commit(p.journalId());
    }
    assertThat(h.storage.snapshot().payouts()).hasSize(40);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int w = 0; w < 6; w++) {
        PayoutWorker worker =
            new PayoutWorker(
                h.storage, h.economy, h.clock, 3, Duration.ofSeconds(1), Duration.ofSeconds(8));
        tasks.add(() -> {
          int done = 0;
          for (int i = 0; i < 20; i++) {
            done += worker.runOnce(5).completed();
          }
          return done;
        });
      }
      int total = 0;
      for (Future<Integer> f : pool.invokeAll(tasks)) {
        total += f.get();
      }
      assertThat(total).isEqualTo(40);
    } finally {
      pool.shutdownNow();
    }
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(40);
    assertThat(h.economy.duplicateReferences()).isEmpty();
    assertThat(h.storage.snapshot().payouts())
        .allSatisfy(p -> assertThat(p.state()).isEqualTo(PayoutRepository.State.COMPLETED));
    assertThat(InMemoryEconomy.class).isNotNull();
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }
}
