package dev.orderhub.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.money.Money;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigRecordsTest {

  @Test
  void noPermissionGivesDefaultLimit() {
    assertThat(Limits.defaults().maxOpenOrders(p -> false)).isEqualTo(3);
  }

  @Test
  void highestMatchingTierWins() {
    Limits limits = Limits.defaults();
    assertThat(limits.maxOpenOrders(Set.of("orderhub.orders.5")::contains)).isEqualTo(5);
    assertThat(limits.maxOpenOrders(Set.of("orderhub.orders.5", "orderhub.orders.25")::contains))
        .isEqualTo(25);
    assertThat(
            limits.maxOpenOrders(
                Set.of(
                        "orderhub.orders.50",
                        "orderhub.orders.5",
                        "orderhub.orders.10",
                        "orderhub.orders.25")
                    ::contains))
        .isEqualTo(50);
  }

  @Test
  void tierBelowDefaultNeverLowersTheLimit() {
    Limits limits =
        new Limits(1, 100, 10, 8, List.of(new Limits.Tier("low", 2), new Limits.Tier("mid", 9)));
    assertThat(limits.maxOpenOrders("low"::equals)).isEqualTo(8);
    assertThat(limits.maxOpenOrders(p -> true)).isEqualTo(9);
  }

  @Test
  void limitsRejectInvalidValues() {
    assertThatThrownBy(() -> new Limits(0, 10, 1, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits(10, 5, 1, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits(1, Money.MAX + 1, 1, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits(1, 5, 0, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits.Tier("x", 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void feesRoundHalfEven() {
    Fees fees = new Fees(250, 500, true); // 2,5 % und 5 %
    assertThat(fees.createFee(100, false)).isEqualTo(2); // 2,5 -> 2 (gerade)
    assertThat(fees.createFee(300, false)).isEqualTo(8); // 7,5 -> 8 (gerade)
    assertThat(fees.createFee(1_000, false)).isEqualTo(25);
    assertThat(fees.salesTax(10, false)).isEqualTo(0); // 0,5 -> 0
    assertThat(fees.salesTax(30, false)).isEqualTo(2); // 1,5 -> 2
    assertThat(fees.salesTax(1_999, false)).isEqualTo(100); // 99,95 -> 100
  }

  @Test
  void exemptionOnlyWorksWhenEnabled() {
    Fees on = new Fees(1_000, 1_000, true);
    Fees off = new Fees(1_000, 1_000, false);
    assertThat(on.createFee(1_000, true)).isZero();
    assertThat(on.salesTax(1_000, true)).isZero();
    assertThat(off.createFee(1_000, true)).isEqualTo(100);
    assertThat(off.salesTax(1_000, true)).isEqualTo(100);
    assertThat(on.createFee(1_000, false)).isEqualTo(100);
  }

  @Test
  void feesRejectOutOfRangeBasisPoints() {
    assertThatThrownBy(() -> new Fees(-1, 0, true)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Fees(0, 10_001, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expiryValidatesLifetime() {
    assertThatThrownBy(() -> new Expiry(Duration.ZERO, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Expiry(Duration.ofSeconds(-1), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Expiry.defaults().defaultLifetime()).isEqualTo(Duration.ofDays(7));
  }

  @Test
  void aTierOnlyLowersTheFeeAndTheLowestMatchingTierWins() {
    Fees fees =
        new Fees(
            200,
            0,
            true,
            List.of(new Fees.Tier("a", 100), new Fees.Tier("b", 50), new Fees.Tier("c", 300)),
            List.of(),
            java.time.ZoneOffset.UTC);
    java.time.Instant now = java.time.Instant.parse("2026-11-28T10:00:00Z");
    assertThat(fees.createFeeBpAt(p -> false, now)).isEqualTo(200);
    assertThat(fees.createFeeBpAt(p -> p.equals("a"), now)).isEqualTo(100);
    assertThat(fees.createFeeBpAt(p -> p.equals("a") || p.equals("b"), now)).isEqualTo(50);
    assertThat(fees.createFeeBpAt(p -> p.equals("c"), now)).isEqualTo(200);
    assertThat(fees.isOfferedCreateFeeBp(50, now)).isTrue();
    assertThat(fees.isOfferedCreateFeeBp(200, now)).isTrue();
    assertThat(fees.isOfferedCreateFeeBp(300, now)).isFalse();
    assertThat(fees.isOfferedCreateFeeBp(7, now)).isFalse();
  }

  @Test
  void aWindowOverMidnightCoversBothSidesAndTheTaxCanDifferFromTheFee() {
    Fees.Window night =
        new Fees.Window(
            "night",
            java.util.Set.of(),
            java.time.LocalTime.of(22, 0),
            java.time.LocalTime.of(2, 0),
            0,
            500);
    Fees fees = new Fees(100, 200, true, List.of(), List.of(night), java.time.ZoneOffset.UTC);
    assertThat(fees.baseCreateFeeBpAt(java.time.Instant.parse("2026-11-28T23:30:00Z"))).isZero();
    assertThat(fees.baseCreateFeeBpAt(java.time.Instant.parse("2026-11-29T01:59:00Z"))).isZero();
    assertThat(fees.baseCreateFeeBpAt(java.time.Instant.parse("2026-11-28T12:00:00Z")))
        .isEqualTo(100);
    assertThat(fees.salesTaxBpAt(java.time.Instant.parse("2026-11-28T23:30:00Z"))).isEqualTo(500);
    assertThat(fees.salesTaxBpAt(java.time.Instant.parse("2026-11-28T12:00:00Z"))).isEqualTo(200);
  }

  @Test
  void aPriceBandIncludesItsBoundsAndRejectsNonsense() {
    var band = new Limits.PriceBand(100, 500);
    assertThat(band.contains(100)).isTrue();
    assertThat(band.contains(500)).isTrue();
    assertThat(band.contains(99)).isFalse();
    assertThat(band.contains(501)).isFalse();
    assertThatThrownBy(() -> new Limits.PriceBand(0, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Limits.PriceBand(9, 5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
