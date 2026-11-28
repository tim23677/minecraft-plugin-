package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.OrderQuery;
import org.junit.jupiter.api.Test;

class MarketArgsTest {

  private static MarketArgs.Result parse(String... args) {
    String[] all = new String[args.length + 1];
    all[0] = "market";
    System.arraycopy(args, 0, all, 1, args.length);
    return MarketArgs.parse(all);
  }

  @Test
  void noArgumentsMeansTheDefaultFilter() {
    var r = parse();
    assertThat(r.ok()).isTrue();
    assertThat(r.filter()).isEqualTo(MarketFlow.Filter.all());
  }

  @Test
  void aSortNameIsASortAndAnythingElseIsSearchText() {
    assertThat(parse("highest-price").filter().sort()).isEqualTo(OrderQuery.Sort.HIGHEST_PRICE);
    assertThat(parse("HIGHEST_DEMAND").filter().sort()).isEqualTo(OrderQuery.Sort.HIGHEST_DEMAND);
    var text = parse("diamond", "sword").filter();
    assertThat(text.text()).isEqualTo("diamond_sword");
    assertThat(text.sort()).isEqualTo(OrderQuery.Sort.NEWEST);
    assertThat(parse("price-asc").filter().text()).isEqualTo("price_asc");
  }

  @Test
  void priceBoundsAcceptSuffixesInAnyPosition() {
    var f = parse("min=1.5k", "diamond", "max=2m").filter();
    assertThat(f.minPrice()).isEqualTo(150_000L);
    assertThat(f.maxPrice()).isEqualTo(200_000_000L);
    assertThat(f.text()).isEqualTo("diamond");
  }

  @Test
  void aSortCanBeCombinedWithABound() {
    var f = parse("lowest-price", "max=50").filter();
    assertThat(f.sort()).isEqualTo(OrderQuery.Sort.LOWEST_PRICE);
    assertThat(f.maxPrice()).isEqualTo(5_000L);
    assertThat(f.minPrice()).isNull();
  }

  @Test
  void unreadableOrInvertedBoundsAreReportedWithTheirText() {
    assertThat(parse("min=abc").invalid()).isEqualTo("min=abc");
    assertThat(parse("max=").invalid()).isEqualTo("max=");
    assertThat(parse("max=-5").invalid()).isEqualTo("max=-5");
    assertThat(parse("min=100", "max=10").ok()).isFalse();
  }
}
