package dev.orderhub.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarketMathTest {

  @Test
  void medianOddAndEven() {
    assertThat(MarketMath.median(List.of(5L, 1L, 3L))).isEqualTo(3);
    assertThat(MarketMath.median(List.of(1L, 2L, 3L, 4L))).isEqualTo(2);
    assertThatThrownBy(() -> MarketMath.median(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tradeSummaryIgnoresOldTradesAndWeightsAverage() {
    List<Trade> trades =
        List.of(new Trade(100, 1, 10), new Trade(200, 3, 20), new Trade(999, 5, 1));
    var s = MarketMath.summarizeTrades(trades, 10).orElseThrow();
    assertThat(s.trades()).isEqualTo(2);
    assertThat(s.volumeUnits()).isEqualTo(4);
    assertThat(s.volumeMoney()).isEqualTo(700);
    assertThat(s.averageUnitPrice()).isEqualTo(175);
  }

  @Test
  void noTradesInWindowGivesEmpty() {
    assertThat(MarketMath.summarizeTrades(List.of(new Trade(1, 1, 5)), 100)).isEmpty();
  }

  @Test
  void demandSummaryOnlyCountsOpenUnits() {
    var s =
        MarketMath.summarizeDemand(
                List.of(
                    new OpenOrderView(800, 64),
                    new OpenOrderView(700, 0),
                    new OpenOrderView(900, 36)))
            .orElseThrow();
    assertThat(s.orders()).isEqualTo(2);
    assertThat(s.openUnits()).isEqualTo(100);
    assertThat(s.lowestUnitPrice()).isEqualTo(800);
    assertThat(s.highestUnitPrice()).isEqualTo(900);
    assertThat(s.averageUnitPrice()).isEqualTo((800L * 64 + 900L * 36) / 100);
  }

  @Test
  void recommendationNeedsEnoughRealTrades() {
    List<Trade> few = List.of(new Trade(800, 1, 1), new Trade(900, 1, 2));
    assertThat(MarketMath.recommend(few, 0, 5)).isEmpty();
    List<Trade> enough =
        List.of(
            new Trade(700, 1, 1),
            new Trade(800, 1, 2),
            new Trade(800, 1, 3),
            new Trade(900, 1, 4),
            new Trade(1000, 1, 5));
    var r = MarketMath.recommend(enough, 0, 5).orElseThrow();
    assertThat(r.low()).isEqualTo(800);
    assertThat(r.median()).isEqualTo(800);
    assertThat(r.high()).isEqualTo(900);
    assertThat(r.trades()).isEqualTo(5);
  }

  @Test
  void recommendationIgnoresTradesBeforeWindow() {
    List<Trade> trades =
        List.of(
            new Trade(1, 1, 1), new Trade(1, 1, 2), new Trade(500, 1, 100), new Trade(600, 1, 101));
    assertThat(MarketMath.recommend(trades, 50, 2).orElseThrow().low()).isEqualTo(500);
  }

  @Test
  void invalidInputsAreRejected() {
    assertThatThrownBy(() -> new Trade(-1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Trade(1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OpenOrderView(1, -1)).isInstanceOf(IllegalArgumentException.class);
  }
}
