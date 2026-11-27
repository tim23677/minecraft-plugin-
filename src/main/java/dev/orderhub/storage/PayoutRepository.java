package dev.orderhub.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Warteschlange fuer Auszahlungen und Rueckerstattungen ({@code payout_queue}, {@code
 * refund_queue}).
 */
public interface PayoutRepository {

  /** Art der Zahlung. */
  enum Kind {
    PAYOUT,
    REFUND
  }

  /** Zustand einer Zahlung. REVIEW bedeutet: unklar, nur manuell aufloesbar. */
  enum State {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    REVIEW
  }

  record Item(
      UUID id,
      UUID txId,
      UUID player,
      Kind kind,
      long amount,
      State state,
      int attempts,
      long updatedAt) {}

  /** Legt eine Zahlung PENDING an. Pro {@code (txId, kind)} gibt es hoechstens eine Zeile. */
  void enqueue(UUID txId, UUID player, Kind kind, long amount, long now);

  /** Reserviert bis zu {@code limit} PENDING-Zahlungen per Compare-and-Set auf IN_PROGRESS. */
  List<Item> claimBatch(int limit, long now);

  Optional<Item> findByTx(UUID txId, Kind kind);

  /** IN_PROGRESS nach COMPLETED. Liefert false, wenn der Zustand nicht mehr IN_PROGRESS ist. */
  boolean markCompleted(UUID id, long now);

  /** IN_PROGRESS nach PENDING (nur bei sicher nicht ausgefuehrter Zahlung), erhoeht attempts. */
  boolean release(UUID id, long now);

  /** IN_PROGRESS nach REVIEW (unklarer Ausgang). */
  boolean markReview(UUID id, String reason, long now);

  /**
   * Wie {@link #markReview}, aber nur wenn die Zeile IN_PROGRESS ist und {@code updated_at ==
   * expectedUpdatedAt} (Compare-and-Set). Fuer die Recovery: ein anderer Server, der die Zeile
   * zwischenzeitlich fortgeschrieben hat, wird nicht ueberschrieben.
   */
  boolean markReviewIfUnchanged(UUID id, long expectedUpdatedAt, String reason, long now);

  /**
   * Loest eine Zeile in REVIEW auf (Compare-and-Set auf den Zustand REVIEW) und setzt sie auf
   * {@code target} (COMPLETED oder FAILED). Fuer die Admin-Aufloesung von MANUAL_REVIEW.
   *
   * @return {@code true}, wenn eine Zeile in REVIEW geaendert wurde
   */
  boolean resolveReview(UUID txId, Kind kind, State target, long now);

  List<Item> findByState(State state, int limit);
}
