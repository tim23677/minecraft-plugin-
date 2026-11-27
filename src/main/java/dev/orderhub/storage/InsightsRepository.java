package dev.orderhub.storage;

import dev.orderhub.market.OpenOrderView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Abgeleitete Daten: Spielerstatistik, Handelsverlauf, Favoriten und Benachrichtigungen. Alle
 * Schreibvorgaenge sind idempotent oder reine Zaehler, ihr Verlust gefaehrdet nie Geld oder Items.
 */
public interface InsightsRepository {

  /** Aggregierte Zahlen eines Spielers. */
  record PlayerStats(
      UUID player,
      long ordersCreated,
      long ordersCompleted,
      long itemsDelivered,
      long itemsReceived,
      long moneyEarned,
      long moneySpent) {}

  /** Ein abgeschlossenes Handelsgeschaeft. */
  record TradeRow(
      UUID journalId, long orderId, String itemKey, long unitPrice, int quantity, long at) {}

  /**
   * Favorit eines Spielers. {@code minPriceAlert}: Alarm, wenn ein neuer Auftrag mindestens diesen
   * Stueckpreis bietet, sonst {@code null}.
   */
  record Favorite(UUID player, String itemKey, Long minPriceAlert, Long lastAlertAt) {}

  record Note(long id, UUID player, String kind, String payload, long createdAt) {}

  void recordOrderCreated(UUID player, long now);

  void recordOrderCompleted(UUID player, long now);

  /** Lieferung: Lieferant bekommt Einnahmen (netto), Auftraggeber Ausgaben (brutto). */
  void recordDelivery(UUID supplier, UUID buyer, int quantity, long gross, long net, long now);

  Optional<PlayerStats> stats(UUID player);

  /**
   * {@code true}, wenn neu eingetragen. Wiederholung mit gleicher Journal-Id zaehlt nicht doppelt.
   */
  boolean recordTrade(
      UUID journalId, long orderId, String itemKey, long unitPrice, int quantity, long now);

  List<TradeRow> tradesSince(String itemKey, long since, int limit);

  /** Offene Nachfrage (ACTIVE) fuer ein Item. */
  List<OpenOrderView> openDemand(String itemKey, int limit);

  /** Items mit aktiven Auftraegen oder Handel, meistgehandelte zuerst. */
  List<String> activeItemKeys(int limit);

  /** Loescht Handelsverlauf vor {@code cutoff}. Liefert die Anzahl. */
  int purgeTradesBefore(long cutoff);

  boolean addFavorite(UUID player, String itemKey, long now);

  boolean removeFavorite(UUID player, String itemKey);

  List<Favorite> favorites(UUID player);

  /** Setzt oder loescht ({@code null}) den Preisalarm eines vorhandenen Favoriten. */
  boolean setAlert(UUID player, String itemKey, Long minPrice);

  /** Favoriten, deren Alarm bei diesem Stueckpreis ausloest und nicht kuerzlich ausgeloest hat. */
  List<Favorite> alertsFor(String itemKey, long unitPrice, long notAfter);

  void touchAlert(UUID player, String itemKey, long now);

  /** {@code true}, wenn neu. Gleiche {@code dedupeKey} je Spieler wird nicht doppelt angelegt. */
  boolean addNote(UUID player, String kind, String payload, String dedupeKey, long now);

  List<Note> pendingNotes(UUID player, int limit);

  /**
   * Offene Hinweise fuer mehrere Spieler auf einmal (aelteste zuerst), hoechstens {@code limit}.
   */
  List<Note> pendingNotesForPlayers(java.util.Collection<UUID> players, int limit);

  /**
   * Beansprucht Hinweise atomar: nur wer die Zeile tatsaechlich von "offen" auf "zugestellt" setzt,
   * bekommt die Id zurueck. Zwei Server, die gleichzeitig dieselben Hinweise lesen, liefern so
   * jeden genau einmal aus.
   */
  List<Long> claimNotes(List<Long> ids, long now);

  void markNotesDelivered(List<Long> ids, long now);
}
