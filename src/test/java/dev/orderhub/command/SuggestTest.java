package dev.orderhub.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestTest {

  private static final List<String> OPTIONS =
      List.of("market", "mine", "sell", "stats", "template");

  @Test
  void findsTheNearestSubcommandForATypo() {
    assertThat(Suggest.closest("marekt", OPTIONS)).hasValue("market");
    assertThat(Suggest.closest("SELL", OPTIONS)).hasValue("sell");
    assertThat(Suggest.closest("tempalte", OPTIONS)).hasValue("template");
  }

  @Test
  void suggestsNothingForUnrelatedOrEmptyInput() {
    assertThat(Suggest.closest("xyzxyz", OPTIONS)).isEmpty();
    assertThat(Suggest.closest("", OPTIONS)).isEmpty();
    assertThat(Suggest.closest("a".repeat(40), OPTIONS)).isEmpty();
  }

  @Test
  void aShortInputNeverMatchesEverything() {
    assertThat(Suggest.closest("x", OPTIONS)).isEmpty();
  }

  @Test
  void distanceIsTheLevenshteinDistance() {
    assertThat(Suggest.distance("kitten", "sitting")).isEqualTo(3);
    assertThat(Suggest.distance("", "abc")).isEqualTo(3);
    assertThat(Suggest.distance("same", "same")).isZero();
  }
}
