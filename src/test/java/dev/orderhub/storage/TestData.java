package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.NewOrder;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxType;
import java.util.UUID;

/** Testdaten-Bausteine. */
final class TestData {

  static final long T0 = 1_000_000L;

  private TestData() {}

  static ItemSnapshot item(String key) {
    return new ItemSnapshot(key, 1, new byte[] {'O', 'H', 'I', '1', 1, 2, 3});
  }

  static NewOrder order(UUID owner, String itemKey, long unitPrice, int qty) {
    return new NewOrder(owner, "srv-a", item(itemKey), unitPrice, qty, 1, T0 + 100_000, false, "blocks");
  }

  static JournalEntry journal(String key, UUID player, Long orderId) {
    return JournalEntry.created(
        key, orderId, player, "srv-a", TxType.DELIVERY, item("minecraft:diamond"), 3, 300, T0);
  }
}
