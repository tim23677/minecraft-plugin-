package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.tx.DeliveryEngine.AbortResult;
import dev.orderhub.tx.DeliveryEngine.CommitResult;
import dev.orderhub.tx.DeliveryEngine.CommitStatus;
import dev.orderhub.tx.DeliveryEngine.ConfirmResult;
import dev.orderhub.tx.DeliveryEngine.PrepareResult;
import dev.orderhub.tx.DeliveryEngine.PrepareStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryEngineTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final long MONEY = 1_000_000;

  private static Harness harness(Fees fees) {
    Harness h = new Harness(fees, Limits.defaults());
    h.economy.setBalance(OWNER, MONEY);
    h.economy.setBalance(SUPPLIER, 0);
    return h;
  }

  private static Order order(Harness h, long id) {
    return h.storage.read(uow -> uow.orders().find(id).orElseThrow());
  }

  private static JournalEntry journal(Harness h, UUID id) {
    return h.storage.read(uow -> uow.journal().find(id).orElseThrow());
  }

  private static void invariants(Harness h) {
    assertThat(dev.orderhub.testsupport.Invariants.check(h, MONEY)).isEmpty();
  }

  @Test
  void fullDeliveryWritesEverythingInOneStep() {
    Harness h = harness(new Fees(0, 1_000, true));
    long id = h.create(OWNER, 200, 5, 1).orderId();

    PrepareResult p = h.delivery.prepare(id, SUPPLIER, 5, "n1");
    assertThat(p.status()).isEqualTo(PrepareStatus.PREPARED);
    assertThat(p.netPayout()).isEqualTo(900);
    assertThat(order(h, id).quantityFilled()).isZero(); // prepare aendert keine Menge
    assertThat(h.delivery.confirmItemsRemoved(p.journalId())).isEqualTo(ConfirmResult.CONFIRMED);

    CommitResult c = h.delivery.commit(p.journalId());
    assertThat(c.status()).isEqualTo(CommitStatus.COMMITTED);
    DeliveryCommitted e = c.event();
    assertThat(e.orderId()).isEqualTo(id);
    assertThat(e.supplier()).isEqualTo(SUPPLIER);
    assertThat(e.owner()).isEqualTo(OWNER);
    assertThat(e.quantity()).isEqualTo(5);
    assertThat(e.unitPrice()).isEqualTo(200);
    assertThat(e.gross()).isEqualTo(1_000);
    assertThat(e.salesTax()).isEqualTo(100);
    assertThat(e.netPayout()).isEqualTo(900);
    assertThat(e.orderCompleted()).isTrue();
    assertThat(e.itemKey()).isEqualTo("minecraft:diamond");

    Order o = order(h, id);
    assertThat(o.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(o.escrowRemaining()).isZero();
    assertThat(h.storage.snapshot().claims())
        .singleElement()
        .satisfies(parcel -> {
          assertThat(parcel.owner()).isEqualTo(OWNER);
          assertThat(parcel.quantity()).isEqualTo(5);
          assertThat(parcel.state()).isEqualTo(ParcelRepository.State.AVAILABLE);
        });
    assertThat(h.storage.snapshot().ledger())
        .filteredOn(l -> l.kind() == LedgerRepository.Kind.PAYOUT)
        .singleElement()
        .satisfies(l -> assertThat(l.amount()).isEqualTo(900));
    assertThat(h.storage.snapshot().ledger())
        .filteredOn(l -> l.kind() == LedgerRepository.Kind.SALES_TAX)
        .singleElement()
        .satisfies(l -> assertThat(l.amount()).isEqualTo(100));
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.PAYOUT_PENDING);
    assertThat(h.storage.snapshot().payouts())
        .singleElement()
        .satisfies(q -> {
          assertThat(q.kind()).isEqualTo(PayoutRepository.Kind.PAYOUT);
          assertThat(q.amount()).isEqualTo(900);
          assertThat(q.player()).isEqualTo(SUPPLIER);
        });
    invariants(h);
    h.drainPayouts();
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(900);
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.PAYOUT_COMPLETED);
    invariants(h);
  }

  @Test
  void partialDeliveriesAddUpAndKeepTheEscrowInvariant() {
    Harness h = harness(Fees.none());
    long id = h.create(OWNER, 100, 10, 1).orderId();
    h.deliver(id, SUPPLIER, 2);
    assertThat(order(h, id).escrowRemaining()).isEqualTo(800);
    assertThat(order(h, id).status()).isEqualTo(OrderStatus.ACTIVE);
    h.deliver(id, SUPPLIER, 3);
    assertThat(order(h, id).escrowRemaining()).isEqualTo(500);
    h.deliver(id, Harness.player(3), 5);
    Order o = order(h, id);
    assertThat(o.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(o.quantityFilled()).isEqualTo(10);
    assertThat(o.escrowRemaining()).isZero();
    h.drainPayouts();
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(500);
    assertThat(h.economy.balance(Harness.player(3))).isEqualTo(500);
    invariants(h);
  }

  @Test
  void minimumDeliveryAppliesButNeverBlocksTheLastItems() {
    Harness h = harness(Fees.none());
    long id = h.create(OWNER, 100, 10, 4).orderId();
    assertThat(h.delivery.prepare(id, SUPPLIER, 3, "a").status())
        .isEqualTo(PrepareStatus.BELOW_MINIMUM);
    h.deliver(id, SUPPLIER, 4);
    h.deliver(id, SUPPLIER, 5); // Rest 1, unter dem Minimum
    assertThat(h.delivery.prepare(id, SUPPLIER, 1, "b").status()).isEqualTo(PrepareStatus.PREPARED);
    assertThat(h.delivery.prepare(id, SUPPLIER, 2, "c").status())
        .isEqualTo(PrepareStatus.EXCEEDS_REMAINING);
  }

  @Test
  void rejectsInvalidRequests() {
    Harness h = harness(Fees.none());
    long id = h.create(OWNER, 100, 5, 1).orderId();
    assertThat(h.delivery.prepare(id, OWNER, 1, "self").status())
        .isEqualTo(PrepareStatus.SELF_DELIVERY);
    assertThat(h.delivery.prepare(id, SUPPLIER, 6, "big").status())
        .isEqualTo(PrepareStatus.EXCEEDS_REMAINING);
    assertThat(h.delivery.prepare(id, SUPPLIER, 0, "zero").status())
        .isEqualTo(PrepareStatus.INVALID_QUANTITY);
    assertThat(h.delivery.prepare(999, SUPPLIER, 1, "x").status())
        .isEqualTo(PrepareStatus.ORDER_NOT_FOUND);
    long version = order(h, id).version();
    h.deliver(id, SUPPLIER, 1);
    assertThat(
            h.delivery
                .prepare(
                    new DeliveryEngine.PrepareRequest(id, SUPPLIER, 1, "stale", version, false))
                .status())
        .isEqualTo(PrepareStatus.STALE_ORDER);
    assertThat(h.storage.snapshot().journal()).hasSize(2); // ESCROW_CREATE und die eine Lieferung
  }

  @Test
  void prepareIsIdempotentPerNonce() {
    Harness h = harness(Fees.none());
    long id = h.create(OWNER, 100, 5, 1).orderId();
    PrepareResult first = h.delivery.prepare(id, SUPPLIER, 2, "same");
    for (int i = 0; i < 9; i++) {
      PrepareResult again = h.delivery.prepare(id, SUPPLIER, 2, "same");
      assertThat(again.status()).isEqualTo(PrepareStatus.ALREADY_PREPARED);
      assertThat(again.journalId()).isEqualTo(first.journalId());
    }
    assertThat(h.storage.snapshot().journal().stream().filter(j -> j.type() == TxType.DELIVERY))
        .hasSize(1);
  }

  @Test
  void commitTenTimesHasExactlyOneEffect() {
    Harness h = harness(new Fees(0, 500, true));
    long id = h.create(OWNER, 100, 10, 1).orderId();
    PrepareResult p = h.delivery.prepare(id, SUPPLIER, 4, "n");
    for (int i = 0; i < 10; i++) {
      assertThat(h.delivery.confirmItemsRemoved(p.journalId()))
          .isIn(ConfirmResult.CONFIRMED, ConfirmResult.ALREADY_CONFIRMED);
    }
    List<CommitStatus> statuses = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      statuses.add(h.delivery.commit(p.journalId()).status());
    }
    assertThat(statuses.get(0)).isEqualTo(CommitStatus.COMMITTED);
    assertThat(statuses.subList(1, 10)).containsOnly(CommitStatus.ALREADY_COMMITTED);
    assertThat(order(h, id).quantityFilled()).isEqualTo(4);
    assertThat(h.storage.snapshot().claims()).hasSize(1);
    assertThat(h.storage.snapshot().payouts()).hasSize(1);
    h.drainPayouts();
    h.drainPayouts();
    assertThat(h.economy.count(dev.orderhub.testsupport.InMemoryEconomy.Op.DEPOSIT)).isEqualTo(1);
    invariants(h);
  }

  @Test
  void commitRequiresConfirmedItems() {
    Harness h = harness(Fees.none());
    long id = h.create(OWNER, 100, 5, 1).orderId();
    PrepareResult p = h.delivery.prepare(id, SUPPLIER, 2, "n");
    assertThat(h.delivery.commit(p.journalId()).status()).isEqualTo(CommitStatus.NOT_CONFIRMED);
    assertThat(h.delivery.commit(UUID.randomUUID()).status()).isEqualTo(CommitStatus.NOT_FOUND);
    assertThat(h.delivery.confirmItemsRemoved(UUID.randomUUID()))
        .isEqualTo(ConfirmResult.NOT_FOUND);
    assertThat(order(h, id).quantityFilled()).isZero();
  }

  @Test
  void commitAfterCancelReturnsTheItemsInsteadOfPaying() {
    Harness h = harness(Fees.none());
    long id = h.create(OWNER, 100, 5, 1).orderId();
    PrepareResult p = h.delivery.prepare(id, SUPPLIER, 3, "n");
    h.delivery.confirmItemsRemoved(p.journalId());
    h.orders.cancelOrder(id, OWNER, false);

    CommitResult c = h.delivery.commit(p.journalId());
    assertThat(c.status()).isEqualTo(CommitStatus.REJECTED_ITEMS_RETURNED);
    assertThat(journal(h, p.journalId()).state()).isEqualTo(TxState.FAILED);
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(r -> {
          assertThat(r.owner()).isEqualTo(SUPPLIER);
          assertThat(r.quantity()).isEqualTo(3);
        });
    assertThat(h.storage.snapshot().claims()).isEmpty();
    assertThat(h.delivery.commit(p.journalId()).status()).isEqualTo(CommitStatus.ALREADY_FAILED);
    h.drainPayouts();
    assertThat(h.economy.balance(SUPPLIER)).isZero();
    invariants(h);
  }

  @Test
  void abortBeforeAndAfterItemRemoval() {
    Harness h = harness(Fees.none());
    long id = h.create(OWNER, 100, 10, 1).orderId();

    PrepareResult early = h.delivery.prepare(id, SUPPLIER, 2, "early");
    assertThat(h.delivery.abort(early.journalId())).isEqualTo(AbortResult.ABORTED);
    assertThat(h.storage.snapshot().returns()).isEmpty();
    assertThat(h.delivery.abort(early.journalId())).isEqualTo(AbortResult.ALREADY_FAILED);
    assertThat(h.delivery.confirmItemsRemoved(early.journalId())).isEqualTo(ConfirmResult.REJECTED);

    PrepareResult late = h.delivery.prepare(id, SUPPLIER, 3, "late");
    h.delivery.confirmItemsRemoved(late.journalId());
    assertThat(h.delivery.abort(late.journalId())).isEqualTo(AbortResult.ABORTED_ITEMS_RETURNED);
    assertThat(h.storage.snapshot().returns())
        .singleElement()
        .satisfies(r -> assertThat(r.quantity()).isEqualTo(3));
    assertThat(h.delivery.commit(late.journalId()).status()).isEqualTo(CommitStatus.ALREADY_FAILED);

    PrepareResult done = h.delivery.prepare(id, SUPPLIER, 1, "done");
    h.delivery.confirmItemsRemoved(done.journalId());
    h.delivery.commit(done.journalId());
    assertThat(h.delivery.abort(done.journalId())).isEqualTo(AbortResult.NOT_ABORTABLE);
    assertThat(h.delivery.abort(UUID.randomUUID())).isEqualTo(AbortResult.NOT_FOUND);
    assertThat(order(h, id).quantityFilled()).isEqualTo(1);
    invariants(h);
  }

  @Test
  void salesTaxRoundsHalfEvenAndCanSwallowTheWholePayout() {
    Harness h = harness(new Fees(0, 5_000, true)); // 50 %
    long id = h.create(OWNER, 3, 1, 1).orderId();
    PrepareResult p = h.delivery.prepare(id, SUPPLIER, 1, "n"); // 3 * 50 % = 1,5 -> 2
    assertThat(p.netPayout()).isEqualTo(1);

    Harness full = harness(new Fees(0, 10_000, true)); // 100 %
    long id2 = full.create(OWNER, 3, 1, 1).orderId();
    full.deliver(id2, SUPPLIER, 1);
    assertThat(full.storage.snapshot().payouts()).isEmpty(); // nichts auszuzahlen
    assertThat(full.storage.snapshot().journal().stream().filter(j -> j.type() == TxType.DELIVERY))
        .singleElement()
        .satisfies(j -> assertThat(j.state()).isEqualTo(TxState.PAYOUT_COMPLETED));
    assertThat(dev.orderhub.testsupport.Invariants.check(full, MONEY)).isEmpty();
  }

  @Test
  void taxExemptionIsHonouredWhenEnabled() {
    Harness h = harness(new Fees(0, 1_000, true));
    long id = h.create(OWNER, 100, 10, 1).orderId();
    PrepareResult exempt =
        h.delivery.prepare(new DeliveryEngine.PrepareRequest(id, SUPPLIER, 10, "e", null, true));
    assertThat(exempt.netPayout()).isEqualTo(1_000);
    PrepareResult taxed = h.delivery.prepare(id, SUPPLIER, 10, "t");
    assertThat(taxed.netPayout()).isEqualTo(900);
  }
}
