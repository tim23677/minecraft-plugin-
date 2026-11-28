package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.OrderOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptionFlagsTest {

  @Test
  void noTokensMeansExactMatching() {
    var r = OptionFlags.parse(List.of());
    assertThat(r.ok()).isTrue();
    assertThat(r.options()).isEqualTo(OrderOptions.EXACT);
  }

  @Test
  void readsAllThreeOptionsInAnyOrderAndCase() {
    var r = OptionFlags.parse(List.of("AutoClaim", "durability=60", "ENCHANTS"));
    assertThat(r.ok()).isTrue();
    assertThat(r.options()).isEqualTo(new OrderOptions(60, true, true));
  }

  @Test
  void acceptsColonAndPercentSignForDurability() {
    assertThat(OptionFlags.parse(List.of("durability:75%")).options().minDurabilityPercent())
        .isEqualTo(75);
    assertThat(OptionFlags.parse(List.of("durability=0")).options().minDurabilityPercent())
        .isZero();
    assertThat(OptionFlags.parse(List.of("durability=100")).options())
        .isEqualTo(OrderOptions.EXACT);
  }

  @Test
  void rejectsOutOfRangeAndMalformedDurability() {
    for (String bad :
        List.of(
            "durability=101", "durability=-1", "durability=", "durability=abc", "durability=1e2")) {
      var r = OptionFlags.parse(List.of(bad));
      assertThat(r.ok()).as(bad).isFalse();
      assertThat(r.invalid()).isEqualTo(bad);
    }
  }

  @Test
  void reportsTheFirstUnknownTokenWithItsOriginalSpelling() {
    var r = OptionFlags.parse(List.of("enchants", "Nonsense", "other"));
    assertThat(r.ok()).isFalse();
    assertThat(r.invalid()).isEqualTo("Nonsense");
  }

  @Test
  void describeListsOnlyWhatIsSet() {
    java.util.function.Function<String, String> text =
        key -> key.equals("durability") ? "dur <percent>" : key;
    assertThat(OptionFlags.describe(OrderOptions.EXACT, text)).isEmpty();
    assertThat(OptionFlags.describe(new OrderOptions(60, true, true), text))
        .isEqualTo("dur 60, enchants, autoclaim");
    assertThat(OptionFlags.describe(new OrderOptions(100, false, true), text))
        .isEqualTo("autoclaim");
  }
}
