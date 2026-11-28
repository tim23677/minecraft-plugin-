package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PriceCheckTest {

  @Test
  void aPriceInsideTheBandIsFine() {
    assertThat(PriceCheck.check(1_000, 1_000, 5)).isEqualTo(PriceCheck.Deviation.NONE);
    assertThat(PriceCheck.check(5_000, 1_000, 5)).isEqualTo(PriceCheck.Deviation.NONE);
    assertThat(PriceCheck.check(200, 1_000, 5)).isEqualTo(PriceCheck.Deviation.NONE);
  }

  @Test
  void aPriceBeyondTheFactorIsFlaggedInTheRightDirection() {
    assertThat(PriceCheck.check(5_001, 1_000, 5)).isEqualTo(PriceCheck.Deviation.TOO_HIGH);
    assertThat(PriceCheck.check(199, 1_000, 5)).isEqualTo(PriceCheck.Deviation.TOO_LOW);
  }

  @Test
  void withoutAReferenceOrFactorNothingIsFlagged() {
    assertThat(PriceCheck.check(999_999, 0, 5)).isEqualTo(PriceCheck.Deviation.NONE);
    assertThat(PriceCheck.check(999_999, 1_000, 0)).isEqualTo(PriceCheck.Deviation.NONE);
    assertThat(PriceCheck.check(999_999, -3, 5)).isEqualTo(PriceCheck.Deviation.NONE);
  }

  @Test
  void hugeNumbersDoNotOverflow() {
    assertThat(PriceCheck.check(Long.MAX_VALUE, Long.MAX_VALUE / 2, 1_000_000))
        .isEqualTo(PriceCheck.Deviation.NONE);
    assertThat(PriceCheck.check(1, Long.MAX_VALUE, 1_000_000))
        .isEqualTo(PriceCheck.Deviation.TOO_LOW);
  }
}
