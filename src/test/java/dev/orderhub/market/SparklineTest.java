package dev.orderhub.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SparklineTest {

  @Test
  void theLowestValueGetsTheLowestBarAndTheHighestTheTallest() {
    assertThat(Sparkline.of(new long[] {100, 200, 300, 400, 500, 600, 700, 800}))
        .isEqualTo("▁▂▃▄▅▆▇█");
  }

  @Test
  void zerosAreShownAsDotsAndAFlatSeriesIsMidHeight() {
    assertThat(Sparkline.of(new long[] {0, 5, 0})).isEqualTo("·▅·");
    assertThat(Sparkline.of(new long[] {7, 7, 7})).isEqualTo("▅▅▅");
    assertThat(Sparkline.of(new long[] {0, 0})).isEqualTo("··");
    assertThat(Sparkline.of(new long[0])).isEmpty();
  }

  @Test
  void dailyAveragesWeightByQuantityAndPlaceTheNewestDayLast() {
    long day = 24L * 3_600_000L;
    long now = 10 * day + 1_000;
    List<Trade> trades =
        List.of(
            new Trade(100, 1, now), // heute
            new Trade(400, 3, now - 10), // heute: (100 + 1200) / 4 = 325
            new Trade(50, 2, now - day), // gestern
            new Trade(999, 1, now - 3 * day), // vorvorgestern minus 1, ausserhalb bei days=3
            new Trade(7, 1, now + day)); // Zukunft zaehlt nicht

    long[] avg = MarketMath.dailyAverages(trades, now, 3);

    assertThat(avg).containsExactly(0, 50, 325);
  }

  @Test
  void dailyAveragesNeedAtLeastOneDay() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> MarketMath.dailyAverages(List.of(), 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
