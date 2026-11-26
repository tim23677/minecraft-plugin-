package dev.orderhub.tx;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Gemeinsame Mechanik fuer Claims (Items an den Auftraggeber) und Returns (Items an Lieferanten).
 * Ablauf: {@link #reserve} reserviert das Paket und merkt die Menge vor, die in das Inventar passt;
 * der Aufrufer legt die Items ins Inventar und meldet die tatsaechlich uebergebene Menge mit
 * {@link #settle}. Journal: CLAIM_PENDING, dann CLAIM_COMPLETED oder DELIVERY_STORED.
 */
public abstract class ParcelTransferEngine {

  /** Ausgang von reserve. */
  public enum ReserveStatus {
    RESERVED,
    ALREADY_SETTLED,
    ALREADY_RELEASED,
    NOT_FOUND,
    NOT_OWNER,
    NOT_AVAILABLE,
    NO_SPACE
  }

  /**
   * @param journalId Kennung der Reservierung, wird an settle uebergeben
   * @param quantity Menge, die der Aufrufer ins Inventar legen darf
   */
  public record Reservation(
      ReserveStatus status, UUID journalId, long parcelId, ItemSnapshot item, int quantity) {
    public boolean reserved() {
      return status == ReserveStatus.RESERVED;
    }
  }

  /** Ausgang von settle. */
  public enum SettleStatus {
    SETTLED,
    RELEASED,
    ALREADY_SETTLED,
    ALREADY_RELEASED,
    NOT_FOUND,
    INVALID_AMOUNT,
    INVALID_STATE
  }

  private final Storage storage;
  private final Function<UnitOfWork, ParcelRepository> repo;
  private final TxType type;
  private final String prefix;

  protected ParcelTransferEngine(
      Storage storage, Function<UnitOfWork, ParcelRepository> repo, TxType type, String prefix) {
    this.storage = storage;
    this.repo = repo;
    this.type = type;
    this.prefix = prefix;
  }

  /**
   * Reserviert das Paket.
   *
   * @param capacity Anzahl Items, die der Aufrufer unterbringen kann
   * @param nonce Idempotenz-Anteil: gleicher Aufruf liefert dieselbe Reservierung
   */
  public Reservation reserve(long parcelId, UUID player, int capacity, String nonce) {
    String key = prefix + parcelId + ":" + player + ":" + nonce;
    return TxRunner.run(storage, uow -> reserveTx(uow, parcelId, player, capacity, key));
  }

  private Reservation reserveTx(
      UnitOfWork uow, long parcelId, UUID player, int capacity, String key) {
    Optional<JournalEntry> existing = uow.journal().findByKey(key);
    if (existing.isPresent()) {
      JournalEntry e = existing.get();
      ReserveStatus status =
          switch (e.state()) {
            case CLAIM_PENDING -> ReserveStatus.RESERVED;
            case CLAIM_COMPLETED -> ReserveStatus.ALREADY_SETTLED;
            default -> ReserveStatus.ALREADY_RELEASED;
          };
      return new Reservation(status, e.id(), parcelId, e.item(), e.quantity());
    }
    ParcelRepository parcels = repo.apply(uow);
    Optional<ParcelRepository.Parcel> found = parcels.find(parcelId);
    if (found.isEmpty()) {
      return new Reservation(ReserveStatus.NOT_FOUND, null, parcelId, null, 0);
    }
    ParcelRepository.Parcel parcel = found.get();
    if (!parcel.owner().equals(player)) {
      return new Reservation(ReserveStatus.NOT_OWNER, null, parcelId, null, 0);
    }
    if (parcel.state() != ParcelRepository.State.AVAILABLE || parcel.quantity() < 1) {
      return new Reservation(ReserveStatus.NOT_AVAILABLE, null, parcelId, null, 0);
    }
    int quantity = Math.min(parcel.quantity(), capacity);
    if (quantity < 1) {
      return new Reservation(ReserveStatus.NO_SPACE, null, parcelId, null, 0);
    }
    JournalEntry created =
        JournalEntry.created(
            key,
            parcel.orderId(),
            player,
            storage.serverId(),
            type,
            parcel.item(),
            quantity,
            0,
            uow.now());
    if (!parcels.reserve(parcelId, created.id(), uow.now())) {
      return new Reservation(ReserveStatus.NOT_AVAILABLE, null, parcelId, null, 0);
    }
    var inserted = uow.journal().insertIfAbsent(created);
    if (!inserted.created()) {
      throw new JournalSteps.StaleJournalException(inserted.entry(), TxState.CREATED);
    }
    JournalSteps.chain(
        uow,
        inserted.entry(),
        "parcel=" + parcelId,
        TxState.PREPARED,
        TxState.COMMITTED,
        TxState.CLAIM_PENDING);
    return new Reservation(ReserveStatus.RESERVED, created.id(), parcelId, parcel.item(), quantity);
  }

  /**
   * Schliesst die Reservierung ab.
   *
   * @param transferred tatsaechlich ins Inventar gelegte Menge; 0 gibt das Paket wieder frei
   */
  public SettleStatus settle(UUID journalId, int transferred) {
    return TxRunner.run(storage, uow -> settleTx(uow, journalId, transferred));
  }

  private SettleStatus settleTx(UnitOfWork uow, UUID journalId, int transferred) {
    Optional<JournalEntry> found = uow.journal().find(journalId);
    if (found.isEmpty() || found.get().type() != type) {
      return SettleStatus.NOT_FOUND;
    }
    JournalEntry e = found.get();
    switch (e.state()) {
      case CLAIM_PENDING:
        break;
      case CLAIM_COMPLETED:
        return SettleStatus.ALREADY_SETTLED;
      case DELIVERY_STORED:
        return SettleStatus.ALREADY_RELEASED;
      default:
        return SettleStatus.INVALID_STATE;
    }
    if (transferred < 0 || transferred > e.quantity()) {
      return SettleStatus.INVALID_AMOUNT;
    }
    Long parcelId = parcelIdOf(uow, e);
    if (parcelId == null) {
      return SettleStatus.INVALID_STATE;
    }
    ParcelRepository parcels = repo.apply(uow);
    if (transferred == 0) {
      parcels.releaseReservation(parcelId, e.id(), uow.now());
      JournalSteps.to(uow, e, TxState.DELIVERY_STORED, "parcel=" + parcelId + ";released");
      return SettleStatus.RELEASED;
    }
    if (!parcels.settle(parcelId, e.id(), transferred, uow.now())) {
      throw new JournalSteps.StaleJournalException(e, TxState.CLAIM_COMPLETED);
    }
    JournalSteps.to(
        uow, e, TxState.CLAIM_COMPLETED, "parcel=" + parcelId + ";transferred=" + transferred);
    return SettleStatus.SETTLED;
  }

  /** Das Paket steht in der Detailnotiz des Eintrags (das Journal hat kein eigenes Feld). */
  private static Long parcelIdOf(UnitOfWork uow, JournalEntry e) {
    return JournalSteps.numberFromDetail(e.detail(), "parcel");
  }

  /**
   * Gibt haengende Reservierungen frei, die seit mindestens {@code staleBefore} (Millis) nicht
   * veraendert wurden. Fuer die Recovery.
   *
   * @return Anzahl freigegebener Reservierungen
   */
  public int releaseHanging(long staleBefore) {
    int released = 0;
    int progress;
    do {
      progress = 0;
      List<ParcelRepository.Parcel> reserved =
          TxRunner.run(storage, uow -> repo.apply(uow).findReserved(1000));
      for (ParcelRepository.Parcel p : reserved) {
        if (p.updatedAt() > staleBefore || p.reservedBy() == null) {
          continue;
        }
        Boolean done =
            TxRunner.run(
                storage,
                uow -> {
                  if (!repo.apply(uow).releaseReservation(p.id(), p.reservedBy(), uow.now())) {
                    return false;
                  }
                  uow.journal()
                      .find(p.reservedBy())
                      .filter(j -> j.state().canTransitionTo(TxState.DELIVERY_STORED))
                      .ifPresent(
                          j ->
                              JournalSteps.to(
                                  uow, j, TxState.DELIVERY_STORED, "parcel=" + p.id() + ";recovered"));
                  return true;
                });
        if (done) {
          progress++;
        }
      }
      released += progress;
    } while (progress > 0);
    return released;
  }
}
