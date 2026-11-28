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

  @Test
  void aReloadSwapsTheWholeSnapshotAtomicallyAndTheShownFeeIsRejectedAfterwards() {
    Harness h = new Harness();
    java.util.concurrent.atomic.AtomicReference<dev.orderhub.config.Rates> ref =
        new java.util.concurrent.atomic.AtomicReference<>(
            new dev.orderhub.config.Rates(
                Limits.defaults(),
                new Fees(100, 0, true),
                new dev.orderhub.config.Expiry(java.time.Duration.ofDays(7), true)));
    OrderService orders = new OrderService(h.storage, h.economy, ref::get);
    h.economy.setBalance(OWNER, 1_000_000);

    long shown = orders.quoteCreateFee(10_000, false); // Anzeige vor dem Reload: 1 %
    assertThat(shown).isEqualTo(100);
    ref.set(
        new dev.orderhub.config.Rates(
            Limits.defaults(),
            new Fees(500, 0, true),
            new dev.orderhub.config.Expiry(java.time.Duration.ofDays(7), true)));

    CreateOrderRequest base = h.request(OWNER, 1_000, 10, 1);
    var afterReload = orders.createOrder(withExpectedFee(h, shown));
    assertThat(afterReload.status()).isEqualTo(CreateStatus.FEE_CHANGED);
    assertThat(h.economy.mutationCount()).isZero();

    long fresh = orders.quoteCreateFee(10_000, false);
    assertThat(fresh).isEqualTo(500);
    var ok = orders.createOrder(withExpectedFee(h, fresh));
    assertThat(ok.status()).isEqualTo(CreateStatus.CREATED);
    assertThat(ok.fee()).isEqualTo(500);
    assertThat(base.expectedFee()).isEqualTo(CreateOrderRequest.FEE_UNCHECKED);
  }

  private static CreateOrderRequest withFeeBp(Harness h, Integer feeBp, long expectedFee) {
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
        expectedFee,
        base.options(),
        feeBp);
  }

  private static Fees tiered() {
    return new Fees(
        200,
        0,
        true,
        java.util.List.of(new Fees.Tier("orderhub.fee.vip", 50)),
        java.util.List.of(),
        java.time.ZoneOffset.UTC);
  }

  @Test
  void aConfiguredTierRateIsChargedAndShownConsistently() {
    Harness h = new Harness(tiered(), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);
    long shown = h.orders.quoteCreateFee(10_000, false, 50);
    assertThat(shown).isEqualTo(50);

    var result = h.orders.createOrder(withFeeBp(h, 50, shown));

    assertThat(result.status()).isEqualTo(CreateStatus.CREATED);
    assertThat(result.fee()).isEqualTo(50);
    assertThat(h.economy.balance(OWNER)).isEqualTo(1_000_000 - 10_000 - 50);
  }

  @Test
  void aRateTheConfigurationDoesNotOfferIsRefusedBeforeAnyCharge() {
    Harness h = new Harness(tiered(), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);

    var result = h.orders.createOrder(withFeeBp(h, 0, CreateOrderRequest.FEE_UNCHECKED));

    assertThat(result.status()).isEqualTo(CreateStatus.FEE_CHANGED);
    assertThat(h.economy.mutationCount()).isZero();
    assertThat(h.storage.snapshot().journal()).isEmpty();
  }

  @Test
  void withoutARateTheBaseRateApplies() {
    Harness h = new Harness(tiered(), Limits.defaults());
    h.economy.setBalance(OWNER, 1_000_000);

    var result = h.orders.createOrder(withFeeBp(h, null, CreateOrderRequest.FEE_UNCHECKED));

    assertThat(result.status()).isEqualTo(CreateStatus.CREATED);
    assertThat(result.fee()).isEqualTo(200);
  }

  @Test
  void aPriceBandOfTheItemIsEnforcedByTheService() {
    var limits =
        new Limits(
            1,
            1_000_000,
            100,
            5,
            java.util.List.of(),
            java.util.Map.of("minecraft:diamond", new Limits.PriceBand(500, 2_000)));
    Harness h = new Harness(new Fees(0, 0, true), limits);
    h.economy.setBalance(OWNER, 10_000_000);
    String key = h.request(OWNER, 1_000, 1, 1).item().itemKey();

    var inside = h.orders.createOrder(h.request(OWNER, 1_000, 1, 1));
    var below = h.orders.createOrder(h.request(OWNER, 400, 1, 1));
    var above = h.orders.createOrder(h.request(OWNER, 2_001, 1, 1));

    assertThat(key).isEqualTo("minecraft:diamond");
    assertThat(inside.status()).isEqualTo(CreateStatus.CREATED);
    assertThat(below.status()).isEqualTo(CreateStatus.INVALID_PRICE);
    assertThat(above.status()).isEqualTo(CreateStatus.INVALID_PRICE);
  }

  @Test
  void theDailyOrderLimitStopsTheNextOrderBeforeAnyCharge() {
    var limits =
        new Limits(
            1, 1_000_000, 100, 50, java.util.List.of(), java.util.Map.of(), new Limits.Daily(2, 0));
    Harness h = new Harness(new Fees(0, 0, true), limits);
    h.economy.setBalance(OWNER, 10_000_000);

    assertThat(h.orders.createOrder(h.request(OWNER, 1_000, 1, 1)).status())
        .isEqualTo(CreateStatus.CREATED);
    assertThat(h.orders.createOrder(h.request(OWNER, 1_000, 1, 1)).status())
        .isEqualTo(CreateStatus.CREATED);
    long mutationsBefore = h.economy.mutationCount();

    var third = h.orders.createOrder(h.request(OWNER, 1_000, 1, 1));

    assertThat(third.status()).isEqualTo(CreateStatus.DAILY_LIMIT);
    assertThat(h.economy.mutationCount()).isEqualTo(mutationsBefore);
    assertThat(h.orders.createOrder(h.request(Harness.player(2), 1_000, 1, 1)).status())
        .isNotEqualTo(CreateStatus.DAILY_LIMIT);
  }
}
