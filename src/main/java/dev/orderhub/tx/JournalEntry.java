package dev.orderhub.tx;

import dev.orderhub.domain.ItemSnapshot;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein Eintrag im Transaction Journal.
 *
 * @param orderId Auftrag oder {@code null}, solange noch keiner existiert
 * @param item Item-Snapshot der Operation oder {@code null}
 * @param quantity betroffene Stueckzahl
 * @param amount betroffener Geldbetrag in Minor Units
 * @param detail letzte Fehlermeldung oder Notiz, nie vertrauliche Daten
 */
public record JournalEntry(
    UUID id,
    String idempotencyKey,
    Long orderId,
    UUID playerId,
    String serverId,
    TxType type,
    ItemSnapshot item,
    int quantity,
    long amount,
    TxState state,
    long version,
    long createdAt,
    long updatedAt,
    String detail) {

  public JournalEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(serverId, "serverId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(state, "state");
    if (idempotencyKey.isBlank() || idempotencyKey.length() > 190) {
      throw new IllegalArgumentException("idempotency key length");
    }
    if (quantity < 0 || amount < 0) {
      throw new IllegalArgumentException("negative quantity or amount");
    }
  }

  /** Neuer Eintrag im Zustand CREATED mit Version 0. */
  public static JournalEntry created(
      String idempotencyKey,
      Long orderId,
      UUID playerId,
      String serverId,
      TxType type,
      ItemSnapshot item,
      int quantity,
      long amount,
      long now) {
    return new JournalEntry(
        UUID.randomUUID(),
        idempotencyKey,
        orderId,
        playerId,
        serverId,
        type,
        item,
        quantity,
        amount,
        TxState.CREATED,
        0,
        now,
        now,
        null);
  }
}
