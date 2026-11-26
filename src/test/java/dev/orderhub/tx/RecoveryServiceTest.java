package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import dev.orderhub.testsupport.InterceptingStorage;
import dev.orderhub.testsupport.Invariants;
import dev.orderhub.testsupport.SimulatedCrash;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryServiceTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final long MONEY = 1_000_000;

  private static Harness harness() {
    Harness h = new Harness(Fees.none(), Limits.defaults());
    h.economy.setBalance(OWNER, MONEY);
    return h;
  }

  private static JournalEntry journal(Harness h, UUID id) {
    return h.storage.read(uow -> uow.journal().find(id).orElseThrow());
  }

  private static PayoutRepository.Item row(Harness h) {
    return h.storage.snapshot().payouts().get(0);
  }

  @Test
  void createdAndPreparedDeliveriesFailWithoutTouchingTheOrder() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var prepared = h.delivery.prepare(id, SUPPLIER, 2, "a");
    JournalEntry created =
        JournalEntry.created(
            "delivery:" + id + ":x:created", id, SUPPLIER, "s", TxType.DELIVERY, null, 1, 100, 1);
    h.storage.putJournal(created);
    h.clock.advance(1_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    // CREATED ohne Reservierung: nichts war passiert. PREPARED mit Reservierung: Items zurueck.
    assertThat(report.failedBeforeItems()).isEqualTo(1);
    assertThat(report.itemsReturned()).isEqualTo(1);
    assertThat(journal(h, prepared.journalId()).state()).isEqualTo(TxState.FAILED);
    assertThat(journal(h, created.id()).state()).isEqualTo(TxState.FAILED);
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.quantity()).isEqualTo(2);
          assertThat(p.owner()).isEqualTo(SUPPLIER);
        });
    assertThat(h.storage.snapshot().orders().get(0).quantityFilled()).isZero();
    assertThat(report.hasEscalations()).isFalse();
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void itemsRemovedWithoutCommitAreReturnedToTheSupplier() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(id, SUPPLIER, 3, "a");
    h.delivery.confirmItemsRemoved(p.journalId());
    h.clock.advance(1_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.itemsReturned()).isEqualTo(1);
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.FAILED);
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(r -> {
          assertThat(r.owner()).isEqualTo(SUPPLIER);
          assertThat(r.quantity()).isEqualTo(3);
          assertThat(r.sourceTx()).isEqualTo(p.journalId());
        });
    assertThat(h.storage.snapshot().orders().get(0).quantityFilled()).isZero();
    assertThat(Invariants.check(h, MONEY)).isEmpty();
    // wiederholter Lauf aendert nichts
    assertThat(h.recovery.sweep().itemsReturned()).isZero();
    assertThat(h.storage.snapshot().returns()).hasSize(1);
  }

  @Test
  void committedDeliveryWithoutQueueRowIsResumedExactlyOnce() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    JournalEntry stuck =
        new JournalEntry(
            UUID.randomUUID(),
            "delivery:" + id + ":c",
            id,
            SUPPLIER,
            "s",
            TxType.DELIVERY,
            null,
            1,
            500,
            TxState.COMMITTED,
            2,
            1,
            1,
            "order=" + id);
    h.storage.putJournal(stuck);
    h.clock.advance(1_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.committedResumed()).isEqualTo(1);
    assertThat(journal(h, stuck.id()).state()).isEqualTo(TxState.PAYOUT_PENDING);
    assertThat(h.storage.snapshot().payouts()).hasSize(1);
    h.recovery.sweep();
    h.drainPayouts();
    h.drainPayouts();
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(500);
  }

  @Test
  void payoutPendingIsLeftForTheWorkerAndMissingRowsAreRepaired() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    h.deliver(id, SUPPLIER, 1); // regulaer PAYOUT_PENDING mit Queue-Zeile
    JournalEntry orphan =
        new JournalEntry(
            UUID.randomUUID(),
            "delivery:" + id + ":o",
            id,
            SUPPLIER,
            "s",
            TxType.DELIVERY,
            null,
            1,
            70,
            TxState.PAYOUT_PENDING,
            3,
            1,
            1,
            null);
    h.storage.putJournal(orphan);
    h.clock.advance(1_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.payoutsVerified()).isEqualTo(2);
    assertThat(h.storage.snapshot().payouts()).hasSize(2);
    h.drainPayouts();
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(2);
  }

  @Test
  void payoutInProgressGoesToManualReviewAndNeverPaysAgain() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(id, SUPPLIER, 2, "a");
    h.delivery.confirmItemsRemoved(p.journalId());
    h.delivery.commit(p.journalId());
    h.economy.failNext(Op.DEPOSIT, Mode.CRASH_AFTER);
    assertThatThrownBy(() -> h.worker.runOnce(5)).isInstanceOf(SimulatedCrash.class);
    h.clock.advance(1_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.payoutsEscalated()).isEqualTo(1);
    assertThat(report.escalations())
        .singleElement()
        .satisfies(e -> {
          assertThat(e.journalId()).isEqualTo(p.journalId());
          assertThat(e.type()).isEqualTo(TxType.DELIVERY);
          assertThat(e.player()).isEqualTo(SUPPLIER);
          assertThat(e.amount()).isEqualTo(200);
        });
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.REVIEW);
    assertThat(h.audit.warnings).hasSize(1);
    assertThat(h.audit.reports).hasSize(1);
    h.drainPayouts();
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void refundInProgressGoesToManualReview() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var cancel = h.orders.cancelOrder(id, OWNER, false);
    h.economy.failNext(Op.DEPOSIT, Mode.CRASH_AFTER);
    assertThatThrownBy(() -> h.worker.runOnce(5)).isInstanceOf(SimulatedCrash.class);
    h.clock.advance(1_000);
    h.restart();

    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.refundsEscalated()).isEqualTo(1);
    assertThat(journal(h, cancel.journalId()).state()).isEqualTo(TxState.MANUAL_REVIEW);
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.REVIEW);
    h.drainPayouts();
    assertThat(h.economy.count(Op.DEPOSIT)).isEqualTo(1);
    assertThat(Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void cleanStartProducesAnEmptyReportWithoutWarning() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    h.deliver(id, SUPPLIER, 5);
    h.drainPayouts();
    h.restart();
    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.hasEscalations()).isFalse();
    assertThat(report.errors()).isZero();
    assertThat(report.failedBeforeItems() + report.itemsReturned() + report.committedResumed())
        .isZero();
    assertThat(h.audit.warnings).isEmpty();
    assertThat(h.audit.reports).hasSize(1);
    assertThat(report.summary()).contains("recovery:");
  }

  @Test
  void journalRefreshedBetweenListingAndProcessingIsNotTouched() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(id, SUPPLIER, 2, "a");
    h.delivery.confirmItemsRemoved(p.journalId());
    h.delivery.commit(p.journalId());
    h.economy.failNext(Op.DEPOSIT, Mode.CRASH_AFTER);
    assertThatThrownBy(() -> h.worker.runOnce(5)).isInstanceOf(SimulatedCrash.class);
    h.clock.advance(Duration.ofHours(1).toMillis());
    h.restart();

    // Aufrufe: 1-4 Auflistungen der ersten Zustaende, 5 Auflistung IN_PROGRESS, 6 Verarbeitung
    InterceptingStorage spy =
        new InterceptingStorage(
            h.storage,
            6,
            () -> {
              // ein anderer Server schreibt Journal und Queue-Zeile fort (frischer Zeitstempel)
              JournalEntry cur =
                  h.storage.read(uow -> uow.journal().find(p.journalId()).orElseThrow());
              h.storage.putJournal(
                  new JournalEntry(
                      cur.id(),
                      cur.idempotencyKey(),
                      cur.orderId(),
                      cur.playerId(),
                      cur.serverId(),
                      cur.type(),
                      cur.item(),
                      cur.quantity(),
                      cur.amount(),
                      cur.state(),
                      cur.version(),
                      cur.createdAt(),
                      h.clock.millis(),
                      cur.detail()));
              TxRunner.run(
                  h.storage,
                  uow -> {
                    var item =
                        uow.payouts()
                            .findByTx(p.journalId(), PayoutRepository.Kind.PAYOUT)
                            .orElseThrow();
                    uow.payouts().release(item.id(), uow.now());
                    uow.payouts().claimBatch(1, uow.now());
                    return null;
                  });
            });
    RecoveryService service =
        new RecoveryService(spy, h.claims, h.returns, h.audit, h.clock, Duration.ofMinutes(10));
    RecoveryReport report = service.runAtStartup();
    assertThat(spy.fired()).isTrue();
    assertThat(report.payoutsEscalated()).isZero();
    assertThat(report.hasEscalations()).isFalse();
    assertThat(report.skippedFresh()).isGreaterThanOrEqualTo(1);
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.IN_PROGRESS);
  }

  @Test
  void queueRowRefreshedBetweenListingAndProcessingIsNotTouched() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    h.orders.cancelOrder(id, OWNER, false);
    h.economy.failNext(Op.DEPOSIT, Mode.CRASH_AFTER);
    assertThatThrownBy(() -> h.worker.runOnce(5)).isInstanceOf(SimulatedCrash.class);
    h.clock.advance(Duration.ofHours(1).toMillis());
    h.restart();

    // 1-5 Journal-Auflistungen, 6 Auflistung der Queue, 7 Verarbeitung der Zeile
    InterceptingStorage spy =
        new InterceptingStorage(
            h.storage,
            7,
            () ->
                TxRunner.run(
                    h.storage,
                    uow -> {
                      var item =
                          uow.payouts().findByState(PayoutRepository.State.IN_PROGRESS, 1).get(0);
                      uow.payouts().release(item.id(), uow.now());
                      uow.payouts().claimBatch(1, uow.now());
                      return null;
                    }));
    RecoveryService service =
        new RecoveryService(spy, h.claims, h.returns, h.audit, h.clock, Duration.ofMinutes(10));
    RecoveryReport report = service.runAtStartup();
    assertThat(spy.fired()).isTrue();
    assertThat(report.refundsEscalated()).isZero();
    assertThat(row(h).state()).isEqualTo(PayoutRepository.State.IN_PROGRESS);
    assertThat(
            h.storage.snapshot().journal().stream().filter(j -> j.state() == TxState.MANUAL_REVIEW))
        .isEmpty();
  }

  @Test
  void freshEntriesAreSkippedWhenStaleAfterIsSet() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 5, 1).orderId();
    var p = h.delivery.prepare(id, SUPPLIER, 2, "a");
    RecoveryService careful =
        new RecoveryService(
            h.storage, h.claims, h.returns, h.audit, h.clock, Duration.ofMinutes(5));
    h.clock.advance(1_000);
    RecoveryReport report = careful.runAtStartup();
    assertThat(report.failedBeforeItems()).isZero();
    assertThat(report.itemsReturned()).isZero();
    assertThat(report.skippedFresh()).isEqualTo(2); // PREPARED-Lieferung und COMMITTED-Erstellung
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.PREPARED);
    h.clock.advance(Duration.ofMinutes(10).toMillis());
    assertThat(careful.sweep().itemsReturned()).isEqualTo(1);
  }
}
