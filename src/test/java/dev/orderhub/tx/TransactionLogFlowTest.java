package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.testsupport.Harness;
import dev.orderhub.testsupport.InMemoryEconomy.Mode;
import dev.orderhub.testsupport.InMemoryEconomy.Op;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jede Geld- und Item-Bewegung hinterlaesst genau eine Zeile mit Transaktions-Id und Zustand. */
class TransactionLogFlowTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);

  private final List<String> lines = new CopyOnWriteArrayList<>();

  @BeforeEach
  void install() {
    TransactionLog.install(lines::add);
  }

  @AfterEach
  void uninstall() {
    TransactionLog.install(null);
  }

  private static Harness harness() {
    Harness h = new Harness(Fees.none(), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);
    return h;
  }

  private List<String> with(String event) {
    return lines.stream().filter(l -> l.contains("event=" + event + " ")).toList();
  }

  @Test
  void aFullTradeLogsEscrowCreationDeliveryAndPayoutWithTheSameTransactionIds() {
    Harness h = harness();
    long orderId = h.create(OWNER, 100, 5, 1).orderId();
    h.deliver(orderId, SUPPLIER, 2);
    h.drainPayouts();

    assertThat(with("ESCROW_WITHDRAW"))
        .singleElement()
        .asString()
        .contains("player=" + OWNER)
        .contains("amount=500")
        .contains("state=OK");
    assertThat(with("ORDER_CREATED")).hasSize(1);
    assertThat(with("DELIVERY_COMMIT"))
        .singleElement()
        .asString()
        .contains("player=" + SUPPLIER)
        .contains("item=minecraft:diamond")
        .contains("qty=2")
        .contains("amount=200")
        .contains("order=" + orderId);
    assertThat(with("PAYOUT_DEPOSIT"))
        .singleElement()
        .asString()
        .contains("player=" + SUPPLIER)
        .contains("state=OK")
        .contains("amount=200");
    assertThat(with("PAYOUT_DEPOSIT_COMPLETED")).hasSize(1);
    String deliveryTx = with("DELIVERY_COMMIT").get(0).split(" tx=")[1].split(" ")[0];
    assertThat(with("PAYOUT_DEPOSIT").get(0)).contains("tx=" + deliveryTx);
  }

  @Test
  void aRefusedWithdrawLogsTheFailureAndNoOrder() {
    Harness h = harness();
    h.economy.setBalance(OWNER, 1);
    h.create(OWNER, 100, 5, 1);
    assertThat(with("ESCROW_WITHDRAW"))
        .singleElement()
        .asString()
        .contains("state=FAILED_INSUFFICIENT");
    assertThat(with("ORDER_CREATED")).isEmpty();
  }

  @Test
  void anUnclearWithdrawLogsUnknown() {
    Harness h = harness();
    h.economy.failNext(Op.WITHDRAW, Mode.THROW_AFTER);
    h.create(OWNER, 100, 5, 1);
    assertThat(with("ESCROW_WITHDRAW")).singleElement().asString().contains("state=UNKNOWN");
  }

  @Test
  void aRejectedDepositLogsTheResultAndNoCompletion() {
    Harness h = harness();
    long orderId = h.create(OWNER, 100, 5, 1).orderId();
    h.deliver(orderId, SUPPLIER, 1);
    h.economy.failNext(Op.DEPOSIT, Mode.RETURN_FAILED);
    h.worker.runOnce(10);
    assertThat(with("PAYOUT_DEPOSIT")).singleElement().asString().contains("state=FAILED");
    assertThat(with("PAYOUT_DEPOSIT_COMPLETED")).isEmpty();
  }

  @Test
  void aDeliveryThatIsNotCommittedLogsNoItemMovement() {
    Harness h = harness();
    long orderId = h.create(OWNER, 100, 5, 1).orderId();
    var prepared = h.delivery.prepare(orderId, SUPPLIER, 1, UUID.randomUUID().toString());
    h.delivery.abort(prepared.journalId());
    assertThat(with("DELIVERY_COMMIT")).isEmpty();
  }
}
