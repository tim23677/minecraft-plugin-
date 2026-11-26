package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.service.OrderService.CreateOrderRequest;
import dev.orderhub.service.OrderService.CreateStatus;
import dev.orderhub.testsupport.Harness;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeeConsistencyTest {

  private static final UUID OWNER = Harness.player(1);

  private static CreateOrderRequest withExpectedFee(Harness h, long expectedFee) {
    CreateOrderRequest base = h.request(OWNER, 1_000, 10, 1);
    return new CreateOrderRequest(
        base.requestId(),
        base.owner(),
        base.item(),
        base.unitPrice(),
        base.quantity(),
        base.minDelivery(),
        base.lifetime(),
        base.autoRenew(),
        base.category(),
        base.maxOpenOrders(),
        base.feeExempt(),
        expectedFee);
  }

  @Test
  void feeShownAtConfirmationIsTheFeeCharged() {
    Harness h = new Harness(new Fees(250, 0, true), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);
    long shown = h.orders.quoteCreateFee(10_000, false);
    var result = h.orders.createOrder(withExpectedFee(h, shown));
    assertThat(result.status()).isEqualTo(CreateStatus.CREATED);
    assertThat(result.fee()).isEqualTo(shown);
    assertThat(h.economy.balance(OWNER)).isEqualTo(1_000_000 - 10_000 - shown);
  }

  @Test
  void feeChangedBetweenDisplayAndConfirmationAbortsWithoutCharging() {
    // Angezeigt wurde die Gebuehr einer frueheren Konfiguration (1 %), abgerechnet wuerde 2,5 %.
    Harness h = new Harness(new Fees(250, 0, true), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);
    long shownBeforeReload = new Fees(100, 0, true).createFee(10_000, false);
    var result = h.orders.createOrder(withExpectedFee(h, shownBeforeReload));
    assertThat(result.status()).isEqualTo(CreateStatus.FEE_CHANGED);
    assertThat(result.fee()).isEqualTo(h.orders.quoteCreateFee(10_000, false));
    assertThat(h.economy.balance(OWNER)).isEqualTo(1_000_000);
    assertThat(h.economy.mutationCount()).isZero();
    assertThat(h.storage.snapshot().journal()).isEmpty();
  }

  @Test
  void requestsWithoutExpectedFeeAreNotChecked() {
    Harness h = new Harness(new Fees(250, 0, true), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);
    assertThat(h.create(OWNER, 1_000, 10, 1).status()).isEqualTo(CreateStatus.CREATED);
  }
}
