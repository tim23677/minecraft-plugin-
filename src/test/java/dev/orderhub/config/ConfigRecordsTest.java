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
}
