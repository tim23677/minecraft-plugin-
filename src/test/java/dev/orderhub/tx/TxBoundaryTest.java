package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import dev.orderhub.testsupport.SimulatedCrash;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Grenzfaelle im Journal und in der Wiederherstellung: Der Zeitpunkt, ab dem ein Eintrag als
 * "haengend" gilt, ist inklusive, ein frischer Eintrag darf nie angefasst werden. Dazu die kleinen
 * Hilfen des Journals (Vertragspruefungen, Detailnotiz, Transaktionsausfuehrung).
 */
class TxBoundaryTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final Duration STALE = Duration.ofMinutes(5);

  private static Harness harness() {
    Harness h = new Harness(Fees.none(), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);
    return h;
  }

  private static RecoveryService careful(Harness h) {
    return new RecoveryService(h.storage, h.claims, h.returns, h.audit, h.clock, STALE);
  }

  private static JournalEntry journal(Harness h, UUID id) {
    return h.storage.read(uow -> uow.journal().find(id).orElseThrow());
  }

  // ---- Wiederherstellung: Grenze der Veraltung ----

  @Test
  void anEntryExactlyStaleAfterOldIsRecoveredOneMillisecondYoungerIsNot() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(id, SUPPLIER, 2, "a");
    RecoveryService service = careful(h);

    h.clock.advance(STALE.toMillis() - 1);
    RecoveryReport early = service.sweep();
    assertThat(early.itemsReturned()).isZero();
    assertThat(early.skippedFresh()).isPositive();
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.PREPARED);

    h.clock.advance(1);
    RecoveryReport onTheEdge = service.sweep();
    assertThat(onTheEdge.itemsReturned()).isEqualTo(1);
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.FAILED);
  }

  @Test
  void aPayoutInProgressIsOnlyEscalatedOnceItIsStaleAndNeverPaidTwice() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(id, SUPPLIER, 2, "a");
    h.delivery.confirmItemsRemoved(p.journalId());
    h.delivery.commit(p.journalId());
    h.economy.failNext(Op.DEPOSIT, Mode.CRASH_AFTER);
    assertThatThrownBy(() -> h.worker.runOnce(5)).isInstanceOf(SimulatedCrash.class);
    h.restart();
    RecoveryService service = careful(h);

    h.clock.advance(STALE.toMillis() - 1);
    RecoveryReport early = service.sweep();
    assertThat(early.payoutsEscalated()).isZero();
    assertThat(early.hasEscalations()).isFalse();
    assertThat(h.storage.snapshot().payouts().get(0).state())
        .isEqualTo(PayoutRepository.State.IN_PROGRESS);
    assertThat(h.audit.warnings).isEmpty();

    h.clock.advance(1);
    RecoveryReport late = service.sweep();
    assertThat(late.payoutsEscalated()).isEqualTo(1);
    assertThat(late.escalations()).hasSize(1);
    assertThat(h.storage.snapshot().payouts().get(0).state())
        .isEqualTo(PayoutRepository.State.REVIEW);
    assertThat(h.audit.warnings).hasSize(1);
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
  }

  @Test
  void aRefundInProgressIsOnlyEscalatedOnceItIsStale() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var cancel = h.orders.cancelOrder(id, OWNER, false);
    h.economy.failNext(Op.DEPOSIT, Mode.CRASH_AFTER);
    assertThatThrownBy(() -> h.worker.runOnce(5)).isInstanceOf(SimulatedCrash.class);
    h.restart();
    RecoveryService service = careful(h);

    h.clock.advance(STALE.toMillis() - 1);
    assertThat(service.sweep().refundsEscalated()).isZero();
    assertThat(journal(h, cancel.journalId()).state()).isNotEqualTo(TxState.MANUAL_REVIEW);

    h.clock.advance(1);
    RecoveryReport late = service.sweep();
    assertThat(late.refundsEscalated()).isEqualTo(1);
    assertThat(late.escalations())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.player()).isEqualTo(OWNER);
          assertThat(e.amount()).isPositive();
        });
    assertThat(journal(h, cancel.journalId()).state()).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
  }

  @Test
  void theLastReportIsRememberedWithItsTime() {
    Harness h = harness();
    assertThat(h.recovery.lastReport()).isNull();
    assertThat(h.recovery.lastRunAt()).isZero();
    h.clock.advance(42);
    RecoveryReport report = h.recovery.sweep();
    assertThat(h.recovery.lastReport()).isSameAs(report);
    assertThat(h.recovery.lastRunAt()).isEqualTo(Harness.START + 42);
  }

  // ---- RecoveryReport ----

  @Test
  void theReportSummaryNamesEveryCounterWithItsValue() {
    var esc = new RecoveryReport.Escalation(UUID.randomUUID(), TxType.DELIVERY, OWNER, 5, "r");
    RecoveryReport report =
        new RecoveryReport(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, List.of(esc));
    assertThat(report.summary())
        .contains(
            "failedBeforeItems=1",
            "itemsReturned=2",
            "committedResumed=3",
            "payoutsVerified=4",
            "payoutsEscalated=5",
            "refundsEscalated=6",
            "createsEscalated=7",
            "claimsReleased=8",
            "returnsReleased=9",
            "claimsEscalated=10",
            "returnsEscalated=11",
            "skippedFresh=12",
            "errors=13",
            "existingManualReview=14");
    assertThat(report.hasEscalations()).isTrue();
  }

  @Test
  void theReportCopiesItsEscalationList() {
    var list = new java.util.ArrayList<RecoveryReport.Escalation>();
    RecoveryReport report = new RecoveryReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, list);
    list.add(new RecoveryReport.Escalation(UUID.randomUUID(), TxType.DELIVERY, OWNER, 1, "r"));
    assertThat(report.hasEscalations()).isFalse();
    assertThatThrownBy(() -> report.escalations().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- JournalEntry ----

  private static JournalEntry entry(String key, int quantity, long amount) {
    return JournalEntry.created(key, null, OWNER, "s", TxType.DELIVERY, null, quantity, amount, 7);
  }

  @Test
  void aNewJournalEntryStartsInCreatedWithVersionZero() {
    JournalEntry e = entry("k", 3, 9);
    assertThat(e.state()).isEqualTo(TxState.CREATED);
    assertThat(e.version()).isZero();
    assertThat(e.createdAt()).isEqualTo(7);
    assertThat(e.updatedAt()).isEqualTo(7);
    assertThat(e.detail()).isNull();
    assertThat(e.quantity()).isEqualTo(3);
    assertThat(e.amount()).isEqualTo(9);
  }

  @Test
  void journalEntriesRejectBadKeysAndNegativeAmounts() {
    assertThat(entry("x".repeat(190), 0, 0).idempotencyKey()).hasSize(190);
    assertThatThrownBy(() -> entry("x".repeat(191), 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry(" ", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry("", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry("k", -1, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry("k", 1, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThat(entry("k", 0, 0).amount()).isZero();
  }

  // ---- JournalSteps ----

  @Test
  void numberFromDetailReadsTheNamedKeyOnly() {
    assertThat(JournalSteps.numberFromDetail("a=1;b=22;c=-3", "b")).isEqualTo(22L);
    assertThat(JournalSteps.numberFromDetail("a=1;b=22;c=-3", "c")).isEqualTo(-3L);
    assertThat(JournalSteps.numberFromDetail("a=1;b=22", "a")).isEqualTo(1L);
    assertThat(JournalSteps.numberFromDetail("ba=5", "a")).isNull();
    assertThat(JournalSteps.numberFromDetail("a=1", "z")).isNull();
    assertThat(JournalSteps.numberFromDetail("a=x", "a")).isNull();
    assertThat(JournalSteps.numberFromDetail("a=", "a")).isNull();
    assertThat(JournalSteps.numberFromDetail(null, "a")).isNull();
  }

  @Test
  void aStaleJournalEntryRollsTheStepBackAndAChainKeepsTheDetailOnTheLastStep() {
    Harness h = harness();
    JournalEntry created = entry("chain", 1, 1);
    h.storage.putJournal(created);

    JournalEntry end =
        TxRunner.run(h.storage, uow -> JournalSteps.chain(uow, created, "done", TxState.PREPARED));
    assertThat(end.state()).isEqualTo(TxState.PREPARED);
    assertThat(end.detail()).isEqualTo("done");

    // Dieselbe (jetzt veraltete) Version nochmals zu verwenden ist ein Fehler.
    assertThatThrownBy(() ->
            TxRunner.run(
                h.storage, uow -> JournalSteps.to(uow, created, TxState.PREPARED, "again")))
        .isInstanceOf(JournalSteps.StaleJournalException.class);
  }

  // ---- TxRunner ----

  private static Storage failing(Throwable failure) {
    return new Storage() {
      @Override
      public <T> CompletableFuture<T> transact(Function<UnitOfWork, T> work) {
        return CompletableFuture.failedFuture(failure);
      }

      @Override
      public Dialect dialect() {
        return Dialect.SQLITE;
      }

      @Override
      public String serverId() {
        return "s";
      }

      @Override
      public void close() {}
    };
  }

  @Test
  void txRunnerRethrowsRuntimeExceptionsAndErrorsUnchangedAndWrapsCheckedOnes() {
    IllegalStateException runtime = new IllegalStateException("db");
    assertThatThrownBy(() -> TxRunner.run(failing(runtime), uow -> 1)).isSameAs(runtime);
    AssertionError error = new AssertionError("crash");
    assertThatThrownBy(() -> TxRunner.run(failing(error), uow -> 1)).isSameAs(error);
    IOException checked = new IOException("disk");
    assertThatThrownBy(() -> TxRunner.run(failing(checked), uow -> 1))
        .isInstanceOf(TxRunner.StorageFailureException.class)
        .hasCause(checked);
  }

  @Test
  void txRunnerReturnsTheResultOfTheWork() {
    String result = TxRunner.run(harness().storage, uow -> "ok");
    assertThat(result).isEqualTo("ok");
  }
}
