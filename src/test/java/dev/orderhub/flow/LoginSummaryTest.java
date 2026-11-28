package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.storage.InsightsRepository.Note;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginSummaryTest {

  private static Note note(String kind, String payload) {
    return new Note(1, UUID.randomUUID(), kind, payload, 0);
  }

  @Test
  void sumsDeliveriesAndCountsExpiredOrdersAndKeepsAlerts() {
    var summary =
        LoginSummary.of(
            List.of(
                note("delivered", "minecraft:diamond|10|5"),
                note("delivered", "minecraft:diamond|32|7"),
                note("expired", "minecraft:stick|4|9"),
                note("price-alert", "minecraft:gold_ingot|5|100|11")));
    assertThat(summary.deliveredItems()).isEqualTo(42);
    assertThat(summary.deliveries()).isEqualTo(2);
    assertThat(summary.expiredOrders()).isEqualTo(1);
    assertThat(summary.alerts()).hasSize(1);
    assertThat(summary.isEmpty()).isFalse();
  }

  @Test
  void corruptAndUnknownNotesNeverBreakTheSummary() {
    var summary =
        LoginSummary.of(
            List.of(
                note("delivered", "garbage"),
                note("delivered", "x|notanumber|1"),
                note("future-kind", "whatever"),
                note("price-alert", "too|few")));
    assertThat(summary.deliveries()).isEqualTo(2);
    assertThat(summary.deliveredItems()).isZero();
    assertThat(summary.alerts()).isEmpty();
  }

  @Test
  void noNotesGiveAnEmptySummary() {
    assertThat(LoginSummary.of(List.of()).isEmpty()).isTrue();
  }
}
