package dev.orderhub.storage;

import dev.orderhub.domain.NewOrder;
import dev.orderhub.domain.Order;
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

  /** ACTIVE Auftraege mit {@code expiresAt <= now}, aelteste zuerst. */
  List<Order> findExpired(long now, int limit);
}
