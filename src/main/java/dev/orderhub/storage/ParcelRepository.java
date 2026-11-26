package dev.orderhub.storage;

import dev.orderhub.domain.ItemSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistente Itempakete: {@code item_claims} (gelieferte Items fuer den Auftraggeber) und {@code
 * item_returns} (nicht angenommene oder zurueckgerollte Items fuer Lieferanten).
 */
public interface ParcelRepository {

  /** Zustand eines Pakets. */
  enum State {
    AVAILABLE,
    RESERVED,
    CLAIMED
  }

  /**
   * @param quantity noch nicht uebergebene Menge
   * @param orderId zugehoeriger Auftrag oder {@code null}
   * @param reservedBy Journal-Id, die das Paket gerade abholt, sonst {@code null}
   */
  record Parcel(
      long id,
      UUID owner,
      Long orderId,
      ItemSnapshot item,
      int quantity,
      State state,
      UUID reservedBy,
      UUID sourceTx,
      long createdAt,
      long updatedAt) {}

  /**
   * Neues Paket. Pro {@code (sourceTx, owner)} hoechstens eins, wiederholtes add ist wirkungslos.
   */
  long add(UUID owner, Long orderId, ItemSnapshot item, int quantity, UUID sourceTx, long now);

  Optional<Parcel> find(long id);

  List<Parcel> listAvailable(UUID owner, int offset, int limit);

  int countAvailable(UUID owner);

  /** AVAILABLE nach RESERVED fuer {@code tx}. Liefert false, wenn bereits reserviert. */
  boolean reserve(long id, UUID tx, long now);

  /**
   * Schliesst eine Reservierung ab: reduziert {@code quantity} um {@code transferred}. Bleibt Rest,
   * wird das Paket wieder AVAILABLE, sonst CLAIMED. Nur fuer den reservierenden {@code tx}.
   */
  boolean settle(long id, UUID tx, int transferred, long now);

  /** Hebt eine Reservierung ohne Uebergabe auf (Recovery). */
  boolean releaseReservation(long id, UUID tx, long now);

  List<Parcel> findReserved(int limit);
}
