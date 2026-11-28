package dev.orderhub.storage;

import dev.orderhub.domain.NewOrder;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderOptions;
import dev.orderhub.domain.OrderQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistenz der Auftraege. Alle Methoden laufen in einer {@link UnitOfWork}. */
public interface OrderRepository {

  /** Legt den Auftrag an (Version 0, Status ACTIVE) und liefert ihn mit neuer Id. */
  Order insert(NewOrder order, long createdAt);

  Optional<Order> find(long id);

  /** Wie {@link #find}, sperrt die Zeile bis Transaktionsende (MariaDB: FOR UPDATE). */
  Optional<Order> findForUpdate(long id);

  /**
   * Compare-and-Set: schreibt {@code next} nur, wenn die gespeicherte Version {@code next.version()
   * - 1} ist. Liefert {@code false}, wenn die Version veraltet ist.
   */
  boolean update(Order next);

  List<Order> search(OrderQuery query);

  long count(OrderQuery query);

  /**
   * Serialisiert konkurrierende Erstellungen eines Spielers ueber Server hinweg. Muss vor {@link
   * #countOpenByOwner} im selben Transaktionsverlauf aufgerufen werden. MariaDB: Zeile des Spielers
   * in {@code escrow_accounts} per Insert-if-absent anlegen und mit {@code SELECT ... FOR UPDATE}
   * sperren (bis Transaktionsende). SQLite: {@code BEGIN IMMEDIATE} serialisiert bereits, die
   * Methode tut nichts.
   */
  void lockOwner(UUID owner);

  /** Anzahl ACTIVE und PAUSED Auftraege des Spielers. Vorher {@link #lockOwner} aufrufen. */
  int countOpenByOwner(UUID owner);

  /**
   * Wie {@link #countOpenByOwner}, aber ohne Sperre und ohne vorheriges {@link #lockOwner}. Nur zum
   * Anzeigen gedacht (Platzhalter, Statistik): der Wert kann einen Augenblick veraltet sein, darf
   * aber nie Zeilen sperren oder eine Erstellung ausbremsen.
   */
  int countOpenByOwnerUnlocked(UUID owner);

  /**
   * Anzahl ACTIVE Auftraege des Spielers, die zwischen {@code from} (ausschliesslich) und {@code
   * until} (einschliesslich) ablaufen. Nur lesend, ohne Sperre.
   */
  int countExpiringBetween(UUID owner, long from, long until);

  /** ACTIVE Auftraege mit {@code expiresAt <= now}, aelteste zuerst. */
  List<Order> findExpired(long now, int limit);

  /** Optionen des Auftrags. Ohne gespeicherte Zeile {@link OrderOptions#EXACT}. */
  OrderOptions options(long orderId);

  /**
   * Optionen mehrerer Auftraege in einer Abfrage. Auftraege ohne gespeicherte Zeile fehlen in der
   * Antwort, sie gelten als {@link OrderOptions#EXACT}.
   */
  java.util.Map<Long, OrderOptions> optionsFor(java.util.Collection<Long> orderIds);

  /** Speichert die Optionen eines neuen oder bestehenden Auftrags. */
  void saveOptions(long orderId, OrderOptions options);

  /** Zeile fuer die Neuzuordnung von Kategorien. */
  record CategoryRow(long id, String itemKey, String category) {}

  /**
   * Offene Auftraege (ACTIVE, PAUSED) mit einer Kategorie aelterer Versionen (blocks, food, items),
   * aufsteigend nach Id, nur Ids hinter {@code afterId}.
   */
  List<CategoryRow> findLegacyCategories(long afterId, int limit);

  /** Setzt die Kategorie. Aendert weder Version noch Escrow. */
  void setCategory(long orderId, String category);
}
