package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import java.util.Optional;
import java.util.UUID;

/**
 * Rueckgabe-Reservierungen von Lieferungen ({@code delivery_holds}). Eine Reservierung entsteht in
 * derselben Transaktion wie der Journal-Zustand PREPARED und haelt alle Items im Liefermenue. Wird
 * sie nie verbraucht, gibt die Recovery sie als Rueckgabe frei, so dass bei einem Absturz nach dem
 * Einfrieren des Menues keine Items verschwinden.
 */
public interface HoldRepository {

  /** Zustand einer Reservierung. */
  enum State {
    HELD,
    RELEASED,
    CONSUMED
  }

  /**
   * @param txId Journal-Id der Lieferung
   * @param quantity alle Items, die das Menue beim Bestaetigen hielt
   */
  record Hold(
      UUID txId,
      UUID owner,
      Long orderId,
      ItemSnapshot item,
      int quantity,
      State state,
      long createdAt,
      long updatedAt) {}

  /** Legt die Reservierung an. Wiederholung fuer dieselbe {@code txId} ist wirkungslos. */
  void add(UUID txId, UUID owner, Long orderId, ItemSnapshot item, int quantity, long now);

  Optional<Hold> find(UUID txId);

  /** HELD nach {@code target} (RELEASED oder CONSUMED). {@code false}, wenn nicht HELD. */
  boolean finish(UUID txId, State target, long now);
}
