package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.service.AdminService.Resolution;
import dev.orderhub.service.AdminService.ResolveStatus;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminReviewTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);

  /** Eine Lieferung, deren Auszahlung mit unklarem Ausgang in MANUAL_REVIEW endet. */
  private static JournalEntry reviewCase(Harness h) {
    h.economy.setBalance(OWNER, 1_000_000);
    var created = h.create(OWNER, 100, 10, 1);
    h.deliver(created.orderId(), SUPPLIER, 1);
    h.economy.failNext(Op.DEPOSIT, Mode.THROW_AFTER);
    h.worker.runOnce(10);
    return h.storage.snapshot().journal().stream()
        .filter(j -> j.state() == TxState.MANUAL_REVIEW)
        .findFirst()
        .orElseThrow();
  }

  private static AdminService admin(Harness h) {
    return new AdminService(h.storage, h.orders, h.recovery);
  }

  private static TxState state(Harness h, UUID id) {
    return h.storage.read(uow -> uow.journal().find(id).orElseThrow().state());
  }

  @Test
  void paidClosesTheEntryAndTheQueueRowWithoutMovingMoney() {
    Harness h = new Harness();
    JournalEntry e = reviewCase(h);
    long mutations = h.economy.mutationCount();
    var r = admin(h).resolveReview(e.id(), Resolution.PAID, "admin", "checked ledger");
    assertThat(r.status()).isEqualTo(ResolveStatus.RESOLVED);
    assertThat(state(h, e.id())).isEqualTo(TxState.PAYOUT_COMPLETED);
    var row =
        h.storage.read(uow -> uow.payouts().findByTx(e.id(), PayoutRepository.Kind.PAYOUT).get());
    assertThat(row.state()).isEqualTo(PayoutRepository.State.COMPLETED);
    h.worker.runOnce(10);
    assertThat(h.economy.mutationCount()).isEqualTo(mutations);
    java.util.List<dev.orderhub.storage.AuditRepository.Entry> audit =
        h.storage.read(uow -> uow.audit().recent(10));
    assertThat(audit)
        .anyMatch(a -> a.action().equals("review.resolve") && a.reason().equals("checked ledger"));
  }

  @Test
  void discardFailsTheEntryAndMovesNoMoney() {
    Harness h = new Harness();
    JournalEntry e = reviewCase(h);
    long mutations = h.economy.mutationCount();
    var r = admin(h).resolveReview(e.id(), Resolution.DISCARD, "admin", "test data");
    assertThat(r.status()).isEqualTo(ResolveStatus.RESOLVED);
    assertThat(state(h, e.id())).isEqualTo(TxState.FAILED);
    h.worker.runOnce(10);
    assertThat(h.economy.mutationCount()).isEqualTo(mutations);
  }

  @Test
  void refundQueuesExactlyOneRefundAndPaysItOnce() {
    Harness h = new Harness();
    JournalEntry e = reviewCase(h);
    var r = admin(h).resolveReview(e.id(), Resolution.REFUND, "admin", "not paid");
    assertThat(r.status()).isEqualTo(ResolveStatus.RESOLVED);
    assertThat(r.refundTx()).isNotNull();
    assertThat(state(h, e.id())).isEqualTo(TxState.FAILED);
    assertThat(state(h, r.refundTx())).isEqualTo(TxState.REFUND_PENDING);
    long before = h.economy.balance(e.playerId());
    h.worker.runOnce(10);
    h.worker.runOnce(10);
    assertThat(state(h, r.refundTx())).isEqualTo(TxState.REFUND_COMPLETED);
    assertThat(h.economy.balance(e.playerId())).isEqualTo(before + e.amount());
  }

  @Test
  void repeatingTheSameOutcomeIsANoOp() {
    Harness h = new Harness();
    JournalEntry e = reviewCase(h);
    var first = admin(h).resolveReview(e.id(), Resolution.REFUND, "admin", "not paid");
    var again = admin(h).resolveReview(e.id(), Resolution.REFUND, "admin", "not paid");
    assertThat(again.status()).isEqualTo(ResolveStatus.ALREADY_RESOLVED);
    h.worker.runOnce(10);
    long refunds =
        h.storage.snapshot().journal().stream()
            .filter(j -> j.idempotencyKey().equals("review-refund:" + e.id()))
            .count();
    assertThat(refunds).isEqualTo(1);
    assertThat(first.refundTx()).isNotNull();
  }

  @Test
  void aDifferentOutcomeAfterwardsIsAConflict() {
    Harness h = new Harness();
    JournalEntry e = reviewCase(h);
    admin(h).resolveReview(e.id(), Resolution.DISCARD, "admin", "x");
    assertThat(admin(h).resolveReview(e.id(), Resolution.PAID, "admin", "y").status())
        .isEqualTo(ResolveStatus.CONFLICT);
  }

  @Test
  void unknownOrHealthyEntriesAreRejectedAndReasonIsRequired() {
    Harness h = new Harness();
    JournalEntry e = reviewCase(h);
    assertThat(admin(h).resolveReview(UUID.randomUUID(), Resolution.PAID, "a", "r").status())
        .isEqualTo(ResolveStatus.NOT_FOUND);
    h.economy.setBalance(OWNER, 1_000_000);
    var created = h.create(OWNER, 100, 10, 1);
    h.deliver(created.orderId(), SUPPLIER, 1);
    UUID healthy =
        h.storage.snapshot().journal().stream()
            .filter(j -> j.state() == TxState.PAYOUT_PENDING)
            .findFirst()
            .orElseThrow()
            .id();
    assertThat(admin(h).resolveReview(healthy, Resolution.PAID, "a", "r").status())
        .isEqualTo(ResolveStatus.NOT_IN_REVIEW);
    assertThatThrownBy(() -> admin(h).resolveReview(e.id(), Resolution.PAID, "a", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
