package dev.orderhub.tx;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.config.Fees;
import dev.orderhub.testsupport.Harness;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DailyDeliveryLimitTest {

  private static final UUID OWNER = Harness.player(1);
  private static final UUID SUPPLIER = Harness.player(2);
  private static final UUID OTHER = Harness.player(3);

  private static Harness harness() {
    Harness h = new Harness();
    h.economy.setBalance(OWNER, 100_000_000);
    return h;
  }

  @Test
  void deliveriesAboveTheDailyLimitAreRefusedBeforeAnyJournalEntry() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 20, 1).orderId();
    DeliveryEngine engine = new DeliveryEngine(h.storage, () -> Fees.none(), () -> 5);

    var first = engine.prepare(id, SUPPLIER, 4, "n1");
    var tooMuch = engine.prepare(id, SUPPLIER, 2, "n2");
    var exactlyTheRest = engine.prepare(id, SUPPLIER, 1, "n3");

    assertThat(first.status()).isEqualTo(DeliveryEngine.PrepareStatus.PREPARED);
    assertThat(tooMuch.status()).isEqualTo(DeliveryEngine.PrepareStatus.DAILY_LIMIT);
    assertThat(tooMuch.journalId()).isNull();
    assertThat(exactlyTheRest.status()).isEqualTo(DeliveryEngine.PrepareStatus.PREPARED);
    assertThat(
            h.storage.snapshot().journal().stream()
                .filter(e -> e.type() == TxType.DELIVERY)
                .count())
        .isEqualTo(2);
  }

  @Test
  void theLimitIsPerSupplierAndZeroMeansNoLimit() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 20, 1).orderId();
    DeliveryEngine limited = new DeliveryEngine(h.storage, () -> Fees.none(), () -> 3);
    DeliveryEngine unlimited = new DeliveryEngine(h.storage, () -> Fees.none(), () -> 0);

    assertThat(limited.prepare(id, SUPPLIER, 3, "a").ok()).isTrue();
    assertThat(limited.prepare(id, OTHER, 3, "b").ok()).isTrue();
    assertThat(limited.prepare(id, SUPPLIER, 1, "c").status())
        .isEqualTo(DeliveryEngine.PrepareStatus.DAILY_LIMIT);
    assertThat(unlimited.prepare(id, SUPPLIER, 5, "d").ok()).isTrue();
  }

  @Test
  void aRepeatedPrepareWithTheSameNonceIsNotCountedTwice() {
    Harness h = harness();
    long id = h.create(OWNER, 100, 20, 1).orderId();
    DeliveryEngine engine = new DeliveryEngine(h.storage, () -> Fees.none(), () -> 5);

    assertThat(engine.prepare(id, SUPPLIER, 5, "same").ok()).isTrue();
    var again = engine.prepare(id, SUPPLIER, 5, "same");

    assertThat(again.status()).isNotEqualTo(DeliveryEngine.PrepareStatus.DAILY_LIMIT);
  }
}
