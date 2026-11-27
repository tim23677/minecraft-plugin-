package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransactionLogTest {

  private final List<String> lines = new CopyOnWriteArrayList<>();

  @AfterEach
  void uninstall() {
    TransactionLog.install(null);
  }

  @Test
  void moneyLineCarriesTransactionPlayerAmountAndState() {
    TransactionLog.install(lines::add);
    UUID tx = UUID.randomUUID();
    UUID player = UUID.randomUUID();
    TransactionLog.money("PAYOUT_DEPOSIT", tx, player, 12345, "OK");
    assertThat(lines).hasSize(1);
    String line = lines.get(0);
    assertThat(line)
        .contains("event=PAYOUT_DEPOSIT")
        .contains("tx=" + tx)
        .contains("player=" + player)
        .contains("amount=12345")
        .contains("state=OK")
        .startsWith("ts=");
  }

  @Test
  void itemLineCarriesItemQuantityAndOrder() {
    TransactionLog.install(lines::add);
    TransactionLog.items(
        "DELIVERY_COMMIT",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "minecraft:diamond",
        16,
        800,
        "COMMITTED",
        7L);
    assertThat(lines.get(0))
        .contains("item=minecraft:diamond")
        .contains("qty=16")
        .contains("amount=800")
        .endsWith("order=7");
  }

  @Test
  void valuesCannotInjectNewLinesOrFields() {
    TransactionLog.install(lines::add);
    TransactionLog.money("X\nevent=FAKE", null, null, 1, "OK state=HACKED\r\nts=2000");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).doesNotContain("\n").doesNotContain("\r");
    assertThat(lines.get(0).split(" ")).filteredOn(p -> p.startsWith("state=")).hasSize(1);
    assertThat(lines.get(0)).contains("tx=-").contains("player=-");
  }

  @Test
  void overlongValuesAreCut() {
    assertThat(TransactionLog.clean("a".repeat(500))).hasSize(120);
    assertThat(TransactionLog.clean(null)).isEqualTo("-");
    assertThat(TransactionLog.clean("")).isEqualTo("-");
  }

  @Test
  void withoutASinkNothingHappensAndABrokenSinkNeverThrows() {
    TransactionLog.money("E", null, null, 1, "S"); // keine Senke: kein Fehler
    TransactionLog.install(line -> {
      throw new IllegalStateException("disk full");
    });
    TransactionLog.money("E", null, null, 1, "S");
  }
}
