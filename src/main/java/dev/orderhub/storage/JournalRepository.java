package dev.orderhub.storage;

import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxState;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistenz des Transaction Journals. */
public interface JournalRepository {

  /**
   * Legt den Eintrag an, wenn der Idempotency Key unbekannt ist. Bei bekanntem Key wird der
   * vorhandene Eintrag zurueckgegeben, nichts wird veraendert.
   */
  InsertResult insertIfAbsent(JournalEntry entry);

  /** Ergebnis von {@link #insertIfAbsent}. */
  record InsertResult(JournalEntry entry, boolean created) {}

  Optional<JournalEntry> find(UUID id);

  Optional<JournalEntry> findByKey(String idempotencyKey);

  /**
   * Strikter Uebergang als Compare-and-Set auf {@code (id, state, version)}. Wirft {@link
   * dev.orderhub.tx.InvalidTransitionException} bei unzulaessigem Uebergang, liefert {@code false}
   * bei veraltetem Zustand oder veralteter Version. Bei Erfolg steigt die Version um 1.
   */
  boolean transition(JournalEntry current, TxState target, String detail, long now);

  /** Nicht terminale Eintraege in den gegebenen Zustaenden, aelteste zuerst. */
  List<JournalEntry> findByStates(EnumSet<TxState> states, int limit);

  List<JournalEntry> findByPlayer(UUID player, int offset, int limit);

  List<JournalEntry> findByOrder(long orderId);

  /**
   * Summe der Stueckzahl aller Lieferungen des Lieferanten seit {@code since}, ohne gescheiterte.
   */
  long sumDeliveredSince(UUID supplier, long since);

  /**
   * Anzahl der Lieferungen des Lieferanten an Auftraege dieses Auftraggebers seit {@code since},
   * ohne gescheiterte.
   */
  int countDeliveriesBetween(UUID supplier, UUID owner, long since);

  List<JournalEntry> findRecent(int offset, int limit);
}
