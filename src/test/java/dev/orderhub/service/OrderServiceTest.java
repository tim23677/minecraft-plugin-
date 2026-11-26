package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.service.OrderService.ActionStatus;
import dev.orderhub.service.OrderService.CreateOrderRequest;
import dev.orderhub.service.OrderService.CreateResult;
import dev.orderhub.service.OrderService.CreateStatus;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryEconomy;
import dev.orderhub.testsupport.InMemoryStorage;
import dev.orderhub.testsupport.Invariants;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.RecoveryReport;
import dev.orderhub.tx.TxState;
import dev.orderhub.tx.TxType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final long START_BALANCE = 1_000_000L;

  private static Harness withMoney(Fees fees) {
    Harness h = new Harness(fees, Limits.defaults());
    h.economy.setBalance(OWNER, START_BALANCE);
    return h;
  }

  private static Order order(Harness h, long id) {
    return h.storage.read(uow -> uow.orders().find(id).orElseThrow());
  }

  private static List<JournalEntry> journal(Harness h, TxType type) {
    return h.storage.snapshot().journal().stream().filter(j -> j.type() == type).toList();
  }

  private static void assertInvariants(Harness h) {
    assertThat(Invariants.check(h, START_BALANCE)).isEmpty();
  }

  @Test
  void createDebitsEscrowAndFeeAndWritesLedger() {
    Harness h = withMoney(new Fees(250, 500, true));
    CreateResult r = h.create(OWNER, 1_000, 10, 1);

    assertThat(r.status()).isEqualTo(CreateStatus.CREATED);
    assertThat(r.escrow()).isEqualTo(10_000);
    assertThat(r.fee()).isEqualTo(250);
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 10_250);
    Order o = order(h, r.orderId());
    assertThat(o.status()).isEqualTo(OrderStatus.ACTIVE);
    assertThat(o.escrowRemaining()).isEqualTo(10_000);
    assertThat(o.escrowConsistent()).isTrue();
    var ledger = h.storage.snapshot().ledger();
    assertThat(ledger)
        .extracting(LedgerRepository.Entry::kind, LedgerRepository.Entry::amount)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(LedgerRepository.Kind.ESCROW_RESERVE, 10_000L),
            org.assertj.core.groups.Tuple.tuple(LedgerRepository.Kind.CREATE_FEE, 250L));
    assertThat(journal(h, TxType.ESCROW_CREATE))
        .singleElement()
        .satisfies(j -> assertThat(j.state()).isEqualTo(TxState.COMMITTED));
    assertInvariants(h);
  }

  @Test
  void feeIsRoundedHalfEvenAndExemptionSkipsIt() {
    Harness h = withMoney(new Fees(333, 0, true)); // 3,33 %
    CreateResult r = h.create(OWNER, 1_000, 7, 1); // escrow 7000, Gebuehr 233,1 -> 233
    assertThat(r.fee()).isEqualTo(233);
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 7_233);

    CreateOrderRequest exempt =
        new CreateOrderRequest(
            UUID.randomUUID(),
            OWNER,
            Harness.item("minecraft:iron_ingot"),
            1_000,
            7,
            1,
            null,
            false,
            "x",
            100,
            true);
    CreateResult r2 = h.orders.createOrder(exempt);
    assertThat(r2.fee()).isZero();
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 7_233 - 7_000);
    assertInvariants(h);
  }

  @Test
  void validatesAgainstLimits() {
    Limits limits = new Limits(10, 100_000, 50, 3, List.of());
    Harness h = new Harness(Fees.none(), limits);
    h.economy.setBalance(OWNER, START_BALANCE);
    assertThat(h.create(OWNER, 9, 1, 1).status()).isEqualTo(CreateStatus.INVALID_PRICE);
    assertThat(h.create(OWNER, 100_001, 1, 1).status()).isEqualTo(CreateStatus.INVALID_PRICE);
    assertThat(h.create(OWNER, 10, 51, 1).status()).isEqualTo(CreateStatus.INVALID_QUANTITY);
    assertThat(h.create(OWNER, 10, 0, 1).status()).isEqualTo(CreateStatus.INVALID_QUANTITY);
    assertThat(h.create(OWNER, 10, 5, 6).status()).isEqualTo(CreateStatus.INVALID_QUANTITY);
    assertThat(h.create(OWNER, 10, 5, 0).status()).isEqualTo(CreateStatus.INVALID_QUANTITY);
    assertThat(h.create(OWNER, 10, 50, 1).status()).isEqualTo(CreateStatus.CREATED);
    assertThat(h.economy.mutationCount()).isEqualTo(1);
    assertInvariants(h);
  }

  @Test
  void overflowingAmountIsRejectedBeforeAnyWithdraw() {
    Limits limits = new Limits(1, dev.orderhub.money.Money.MAX, 2_000_000, 3, List.of());
    Harness h = new Harness(Fees.none(), limits);
    h.economy.setBalance(OWNER, START_BALANCE);
    CreateResult r = h.create(OWNER, dev.orderhub.money.Money.MAX, 1_000_000, 1);
    assertThat(r.status()).isEqualTo(CreateStatus.INVALID_AMOUNT);
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void insufficientFundsLeavesNothingBehind() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 500);
    CreateResult r = h.create(OWNER, 1_000, 10, 1);
    assertThat(r.status()).isEqualTo(CreateStatus.INSUFFICIENT_FUNDS);
    assertThat(h.economy.balance(OWNER)).isEqualTo(500);
    assertThat(h.storage.snapshot().orders()).isEmpty();
    assertThat(journal(h, TxType.ESCROW_CREATE))
        .singleElement()
        .satisfies(j -> assertThat(j.state()).isEqualTo(TxState.FAILED));
    assertThat(Invariants.check(h, 500)).isEmpty();
  }

  @Test
  void economyOutageAndRefusalAreCleanFailures() {
    Harness h = withMoney(Fees.none());
    h.economy.setUnavailable(true);
    assertThat(h.create(OWNER, 100, 5, 1).status()).isEqualTo(CreateStatus.ECONOMY_UNAVAILABLE);
    h.economy.setUnavailable(false);
    h.economy.failNext(InMemoryEconomy.Op.WITHDRAW, InMemoryEconomy.Mode.RETURN_FAILED);
    assertThat(h.create(OWNER, 100, 5, 1).status()).isEqualTo(CreateStatus.ECONOMY_FAILED);
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE);
    assertThat(h.storage.snapshot().orders()).isEmpty();
    assertThat(journal(h, TxType.ESCROW_CREATE))
        .allSatisfy(j -> assertThat(j.state()).isEqualTo(TxState.FAILED));
    assertInvariants(h);
  }

  @Test
  void uncertainWithdrawGoesToManualReviewAndNeverRetries() {
    Harness h = withMoney(Fees.none());
    h.economy.failNext(InMemoryEconomy.Op.WITHDRAW, InMemoryEconomy.Mode.THROW_AFTER);
    CreateOrderRequest req = h.request(OWNER, 100, 5, 1);
    CreateResult r = h.orders.createOrder(req);
    assertThat(r.status()).isEqualTo(CreateStatus.ECONOMY_UNCERTAIN);
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 500); // Abbuchung lief
    assertThat(h.storage.snapshot().orders()).isEmpty();
    assertThat(journal(h, TxType.ESCROW_CREATE))
        .singleElement()
        .satisfies(j -> assertThat(j.state()).isEqualTo(TxState.MANUAL_REVIEW));
    // dieselbe Anfrage erneut: keine zweite Abbuchung
    CreateResult again = h.orders.createOrder(req);
    assertThat(again.status()).isEqualTo(CreateStatus.FAILED_NEEDS_REVIEW);
    assertThat(h.economy.count(InMemoryEconomy.Op.WITHDRAW)).isEqualTo(1);
    assertInvariants(h);
  }

  @Test
  void sameRequestTenTimesHasExactlyOneEffect() {
    Harness h = withMoney(new Fees(100, 0, true));
    CreateOrderRequest req = h.request(OWNER, 1_000, 10, 1);
    List<CreateResult> results = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      results.add(h.orders.createOrder(req));
    }
    assertThat(results.get(0).status()).isEqualTo(CreateStatus.CREATED);
    assertThat(results.subList(1, 10))
        .allSatisfy(r -> {
          assertThat(r.status()).isEqualTo(CreateStatus.ALREADY_CREATED);
          assertThat(r.orderId()).isEqualTo(results.get(0).orderId());
        });
    assertThat(h.storage.snapshot().orders()).hasSize(1);
    assertThat(h.economy.count(InMemoryEconomy.Op.WITHDRAW)).isEqualTo(1);
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 10_100);
    assertInvariants(h);
  }

  @Test
  void sameRequestInParallelHasExactlyOneEffect() throws Exception {
    Harness h = withMoney(Fees.none());
    CreateOrderRequest req = h.request(OWNER, 1_000, 10, 1);
    ExecutorService pool = Executors.newFixedThreadPool(10);
    try {
      List<Callable<CreateResult>> tasks = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        tasks.add(() -> h.orders.createOrder(req));
      }
      for (Future<CreateResult> f : pool.invokeAll(tasks)) {
        assertThat(f.get().status())
            .isIn(CreateStatus.CREATED, CreateStatus.ALREADY_CREATED, CreateStatus.IN_PROGRESS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(h.storage.snapshot().orders()).hasSize(1);
    assertThat(h.economy.count(InMemoryEconomy.Op.WITHDRAW)).isEqualTo(1);
    assertThat(h.orders.createOrder(req).status()).isEqualTo(CreateStatus.ALREADY_CREATED);
    assertInvariants(h);
  }

  @Test
  void twentyParallelCreationsOfOnePlayerRespectTheLimitAndRefundTheRest() throws Exception {
    Harness h = withMoney(Fees.none());
    h.economy.setBalance(OWNER, 20 * 500L + 100);
    long initial = h.economy.total();
    ExecutorService pool = Executors.newFixedThreadPool(20);
    List<CreateResult> results = new ArrayList<>();
    try {
      List<Callable<CreateResult>> tasks = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        CreateOrderRequest req =
            new CreateOrderRequest(
                UUID.randomUUID(),
                OWNER,
                Harness.item("minecraft:stone"),
                100,
                5,
                1,
                null,
                false,
                "x",
                3,
                false);
        tasks.add(() -> h.orders.createOrder(req));
      }
      for (Future<CreateResult> f : pool.invokeAll(tasks)) {
        results.add(f.get());
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(results).filteredOn(r -> r.status() == CreateStatus.CREATED).hasSize(3);
    assertThat(results)
        .filteredOn(r -> r.status() != CreateStatus.CREATED)
        .hasSize(17)
        .allSatisfy(r ->
            assertThat(r.status())
                .isIn(CreateStatus.LIMIT_REACHED, CreateStatus.FAILED_REFUND_QUEUED));
    assertThat(h.storage.snapshot().orders()).hasSize(3);
    assertThat(h.storage.ownerLockCalls()).isGreaterThanOrEqualTo(20);
    h.drainPayouts();
    assertThat(h.economy.balance(OWNER)).isEqualTo(initial - 3 * 500L);
    assertThat(Invariants.check(h, initial)).isEmpty();
  }

  @Test
  void dbFailureAfterWithdrawRefundsThroughTheQueue() {
    Harness h = withMoney(new Fees(100, 0, true));
    h.storage.failTransaction(2, InMemoryStorage.FailAt.BEFORE_COMMIT); // Erstell-Transaktion
    CreateResult r = h.create(OWNER, 1_000, 10, 1);
    assertThat(r.status()).isEqualTo(CreateStatus.FAILED_REFUND_QUEUED);
    assertThat(h.storage.snapshot().orders()).isEmpty();
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 10_100);
    assertThat(h.storage.snapshot().payouts())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.kind()).isEqualTo(PayoutRepository.Kind.REFUND);
          assertThat(p.amount()).isEqualTo(10_100);
        });
    assertInvariants(h);
    h.drainPayouts();
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE);
    assertThat(journal(h, TxType.ESCROW_CREATE))
        .singleElement()
        .satisfies(j -> assertThat(j.state()).isEqualTo(TxState.REFUND_COMPLETED));
    assertInvariants(h);
  }

  @Test
  void lostAckAfterCommitDoesNotRefundAnExistingOrder() {
    Harness h = withMoney(Fees.none());
    h.storage.failTransaction(2, InMemoryStorage.FailAt.AFTER_COMMIT);
    CreateResult r = h.create(OWNER, 1_000, 10, 1);
    assertThat(r.status()).isEqualTo(CreateStatus.ALREADY_CREATED);
    assertThat(r.orderId()).isNotNull();
    assertThat(h.storage.snapshot().orders()).hasSize(1);
    assertThat(h.storage.snapshot().payouts()).isEmpty();
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 10_000);
    assertInvariants(h);
  }

  @Test
  void totalDbOutageAfterWithdrawKeepsTheJournalForReview() {
    Harness h = withMoney(Fees.none());
    for (int n = 2; n <= 5; n++) {
      h.storage.failTransaction(n, InMemoryStorage.FailAt.BEFORE_WORK);
    }
    CreateResult r = h.create(OWNER, 1_000, 10, 1);
    assertThat(r.status()).isEqualTo(CreateStatus.FAILED_NEEDS_REVIEW);
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 10_000);
    assertThat(journal(h, TxType.ESCROW_CREATE))
        .singleElement()
        .satisfies(j -> assertThat(j.state()).isEqualTo(TxState.PREPARED));
    assertInvariants(h);

    h.restart();
    RecoveryReport report = h.recovery.runAtStartup();
    assertThat(report.createsEscalated()).isEqualTo(1);
    assertThat(report.escalations())
        .singleElement()
        .satisfies(e -> assertThat(e.amount()).isEqualTo(10_000));
    assertThat(journal(h, TxType.ESCROW_CREATE))
        .singleElement()
        .satisfies(j -> assertThat(j.state()).isEqualTo(TxState.MANUAL_REVIEW));
    assertThat(h.audit.warnings).isNotEmpty();
    assertInvariants(h);
  }

  @Test
  void dbDownBeforeWithdrawChangesNothing() {
    Harness h = withMoney(Fees.none());
    h.storage.failTransaction(1, InMemoryStorage.FailAt.BEFORE_WORK);
    CreateResult r = h.create(OWNER, 1_000, 10, 1);
    assertThat(r.status()).isEqualTo(CreateStatus.DB_UNAVAILABLE);
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void cancelRefundsOnlyTheRemainingEscrow() {
    Harness h = withMoney(new Fees(500, 0, true));
    h.economy.setBalance(SUPPLIER, 0);
    long initial = h.economy.total();
    CreateResult c = h.create(OWNER, 1_000, 10, 1); // Escrow 10000, Gebuehr 500
    h.deliver(c.orderId(), SUPPLIER, 4);

    var cancel = h.orders.cancelOrder(c.orderId(), OWNER, false);
    assertThat(cancel.status()).isEqualTo(ActionStatus.OK);
    assertThat(cancel.refunded()).isEqualTo(6_000);
    Order o = order(h, c.orderId());
    assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(o.escrowRemaining()).isZero();
    assertThat(Invariants.check(h, initial)).isEmpty();

    h.drainPayouts();
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 10_500 + 6_000);
    assertThat(h.economy.balance(SUPPLIER)).isEqualTo(4_000);
    assertThat(Invariants.check(h, initial)).isEmpty();
    // zweiter Storno: keine zweite Erstattung
    assertThat(h.orders.cancelOrder(c.orderId(), OWNER, false).status())
        .isEqualTo(ActionStatus.ALREADY_DONE);
    h.drainPayouts();
    assertThat(h.economy.count(InMemoryEconomy.Op.DEPOSIT)).isEqualTo(2);
  }

  @Test
  void cancelChecksOwnerAndState() {
    Harness h = withMoney(Fees.none());
    CreateResult c = h.create(OWNER, 100, 2, 1);
    assertThat(h.orders.cancelOrder(c.orderId(), SUPPLIER, false).status())
        .isEqualTo(ActionStatus.NOT_OWNER);
    assertThat(h.orders.cancelOrder(999, OWNER, false).status()).isEqualTo(ActionStatus.NOT_FOUND);
    assertThat(h.orders.cancelOrder(c.orderId(), SUPPLIER, true).status())
        .isEqualTo(ActionStatus.OK);

    CreateResult done = h.create(OWNER, 100, 2, 1);
    h.deliver(done.orderId(), SUPPLIER, 2);
    assertThat(order(h, done.orderId()).status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(h.orders.cancelOrder(done.orderId(), OWNER, false).status())
        .isEqualTo(ActionStatus.INVALID_STATE);
  }

  @Test
  void pauseResumeAndArchive() {
    Harness h = withMoney(Fees.none());
    CreateResult c = h.create(OWNER, 100, 2, 1);
    assertThat(h.orders.pause(c.orderId(), SUPPLIER, false).status())
        .isEqualTo(ActionStatus.NOT_OWNER);
    assertThat(h.orders.pause(c.orderId(), OWNER, false).status()).isEqualTo(ActionStatus.OK);
    assertThat(h.orders.pause(c.orderId(), OWNER, false).status())
        .isEqualTo(ActionStatus.ALREADY_DONE);
    assertThat(h.delivery.prepare(c.orderId(), SUPPLIER, 1, "n").status())
        .isEqualTo(dev.orderhub.tx.DeliveryEngine.PrepareStatus.ORDER_NOT_ACTIVE);
    assertThat(h.orders.resume(c.orderId(), OWNER, false).status()).isEqualTo(ActionStatus.OK);
    assertThat(h.orders.archive(c.orderId(), OWNER, false).status())
        .isEqualTo(ActionStatus.INVALID_STATE);
    h.orders.cancelOrder(c.orderId(), OWNER, false);
    assertThat(h.orders.archive(c.orderId(), OWNER, false).status()).isEqualTo(ActionStatus.OK);
    assertThat(order(h, c.orderId()).status()).isEqualTo(OrderStatus.ARCHIVED);
    assertThat(h.orders.archive(c.orderId(), OWNER, false).status())
        .isEqualTo(ActionStatus.ALREADY_DONE);
  }

  @Test
  void expiryRefundsOnlyTheRestAndRenewsFundedAutoRenewOrders() {
    Harness h = withMoney(Fees.none());
    h.economy.setBalance(SUPPLIER, 0);
    long initial = h.economy.total();
    CreateOrderRequest shortLived =
        new CreateOrderRequest(
            UUID.randomUUID(),
            OWNER,
            Harness.item("minecraft:coal"),
            1_000,
            10,
            1,
            Duration.ofHours(1),
            false,
            "x",
            100,
            false);
    CreateOrderRequest renewing =
        new CreateOrderRequest(
            UUID.randomUUID(),
            OWNER,
            Harness.item("minecraft:gold_ingot"),
            500,
            4,
            1,
            Duration.ofHours(1),
            true,
            "x",
            100,
            false);
    CreateOrderRequest longLived =
        new CreateOrderRequest(
            UUID.randomUUID(),
            OWNER,
            Harness.item("minecraft:emerald"),
            500,
            4,
            1,
            Duration.ofDays(30),
            false,
            "x",
            100,
            false);
    long a = h.orders.createOrder(shortLived).orderId();
    long b = h.orders.createOrder(renewing).orderId();
    long c = h.orders.createOrder(longLived).orderId();
    h.deliver(a, SUPPLIER, 3);

    h.clock.advance(Duration.ofHours(2).toMillis());
    var report = h.orders.expireDue(10);

    assertThat(report.expired()).isEqualTo(1);
    assertThat(report.renewed()).isEqualTo(1);
    assertThat(order(h, a).status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(order(h, a).escrowRemaining()).isZero();
    assertThat(order(h, b).status()).isEqualTo(OrderStatus.ACTIVE);
    assertThat(order(h, b).expiresAt()).isGreaterThan(h.clock.millis());
    assertThat(order(h, b).escrowRemaining()).isEqualTo(2_000);
    assertThat(order(h, c).status()).isEqualTo(OrderStatus.ACTIVE);
    assertThat(Invariants.check(h, initial)).isEmpty();

    h.drainPayouts();
    // nur der Rest (7 * 1000) wurde erstattet
    assertThat(h.economy.balance(OWNER)).isEqualTo(START_BALANCE - 10_000 - 2_000 - 2_000 + 7_000);
    assertThat(h.orders.expireDue(10).expired()).isZero();
    h.drainPayouts();
    assertThat(h.economy.count(InMemoryEconomy.Op.DEPOSIT)).isEqualTo(2); // Lieferung + Erstattung
    assertThat(Invariants.check(h, initial)).isEmpty();
  }

  @Test
  void expiryDoesNotTouchRunningOrdersAndIsIdempotent() {
    Harness h = withMoney(Fees.none());
    CreateResult c = h.create(OWNER, 100, 3, 1);
    assertThat(h.orders.expireDue(10).expired()).isZero();
    h.clock.advance(Duration.ofDays(8).toMillis());
    assertThat(h.orders.expireDue(10).expired()).isEqualTo(1);
    assertThat(h.orders.expireDue(10).expired()).isZero();
    assertThat(order(h, c.orderId()).status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(h.storage.snapshot().payouts()).hasSize(1);
  }
}
