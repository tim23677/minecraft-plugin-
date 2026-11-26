package dev.orderhub.storage;

import java.util.List;
import java.util.UUID;

/** Append-only Hauptbuch aller Geldbewegungen. Es gibt weder Update noch Delete. */
public interface LedgerRepository {

  /** Art einer Buchung. Korrekturen sind {@code REVERSAL} oder {@code ADMIN_ADJUST}. */
  enum Kind {
    ESCROW_RESERVE,
    CREATE_FEE,
    PAYOUT,
    SALES_TAX,
    REFUND,
    ADMIN_ADJUST,
    REVERSAL,
    FAILED
  }

  /**
   * Buchung. {@code amount} ist immer positiv in Minor Units, die Richtung ergibt sich aus {@code
   * kind}. {@code player} ist {@code null} fuer Systembuchungen.
   */
  record Entry(
      UUID id, UUID txId, UUID player, Kind kind, long amount, long createdAt, String note) {}

  void append(Entry entry);

  List<Entry> findByTx(UUID txId);

  List<Entry> findByPlayer(UUID player, int offset, int limit);
}
