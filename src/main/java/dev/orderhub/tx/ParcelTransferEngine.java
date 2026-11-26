package dev.orderhub.tx;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Gemeinsame Mechanik fuer Claims (Items an den Auftraggeber) und Returns (Items an Lieferanten).
 *
 * <p>Ablauf: {@link #reserve} reserviert das Paket und merkt die Menge vor, die ins Inventar passt
 * (Journal COMMITTED, "Uebergabe nicht begonnen"). {@link #beginHandover} persistiert den Marker
 * "Uebergabe begonnen" (CLAIM_PENDING) und MUSS vor dem Ablegen der Items im Inventar erfolgreich
 * sein. Danach meldet der Aufrufer die tatsaechlich uebergebene Menge mit {@link #settle}
 * (CLAIM_COMPLETED) oder 0 (DELIVERY_STORED).
 *
 * <p>Recovery: Reservierungen ohne Marker gibt die Recovery frei, weil belegbar nie etwas
 * uebergeben wurde. Haengende Reservierungen mit Marker haben unbekannten Ausgang (die Items
 * koennen schon beim Spieler sein) und gehen nach MANUAL_REVIEW, das Paket bleibt RESERVED. Eine
 * automatische Freigabe wuerde sonst ein zweites Abholen erlauben (Item-Dupe).
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
   * @param journalId Kennung der Reservierung, wird an beginHandover und settle uebergeben
   * @param quantity Menge, die der Aufrufer ins Inventar legen darf
   */
  public record Reservation(
      ReserveStatus status, UUID journalId, long parcelId, ItemSnapshot item, int quantity) {
    public boolean reserved() {
      return status == ReserveStatus.RESERVED;
    }
  }

  /** Ausgang von beginHandover. */
  public enum HandoverStatus {
    STARTED,
    ALREADY_STARTED,
    NOT_FOUND,
    /** Reservierung wurde freigegeben oder liegt in MANUAL_REVIEW: nichts uebergeben! */
    NOT_ALLOWED
  }

  /** Ausgang von settle. */
  public enum SettleStatus {
    SETTLED,
    RELEASED,
    ALREADY_SETTLED,
    ALREADY_RELEASED,
    NOT_FOUND,
    INVALID_AMOUNT,
    INVALID_STATE,
    /** Uebergabe gemeldet, obwohl die Reservierung freigegeben wurde: Fehler, wird protokolliert. */
    CONFLICT_RELEASED,
    /** Uebergabe gemeldet, der Eintrag liegt aber in MANUAL_REVIEW: Fehler, wird protokolliert. */
    CONFLICT_UNDER_REVIEW
  }

  /** Ergebnis von {@link #releaseHanging}. */
  public record HangingResult(int released, List<RecoveryReport.Escalation> escalations) {}

  private final Storage storage;
  private final Function<UnitOfWork, ParcelRepository> repo;
  private final TxType type;
  private final String prefix;
  private final AuditSink audit;

  protected ParcelTransferEngine(
      Storage storage,
      Function<UnitOfWork, ParcelRepository> repo,
      TxType type,
      String prefix,
      AuditSink audit) {
    this.storage = storage;
    this.repo = repo;
    this.type = type;
    this.prefix = prefix;
    this.audit = audit;
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
            case COMMITTED, CLAIM_PENDING -> ReserveStatus.RESERVED;
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
        uow, inserted.entry(), "parcel=" + parcelId, TxState.PREPARED, TxState.COMMITTED);
    return new Reservation(ReserveStatus.RESERVED, created.id(), parcelId, parcel.item(), quantity);
  }

  /**
   * Persistiert den Marker "Uebergabe begonnen" (COMMITTED nach CLAIM_PENDING). Aufruf VOR dem
   * Ablegen der Items im Inventar; nur bei {@code STARTED} oder {@code ALREADY_STARTED} darf
   * uebergeben werden.
   */
  public HandoverStatus beginHandover(UUID journalId) {
    return TxRunner.run(
        storage,
        uow -> {
          Optional<JournalEntry> found = uow.journal().find(journalId);
          if (found.isEmpty() || found.get().type() != type) {
            return HandoverStatus.NOT_FOUND;
          }
          JournalEntry e = found.get();
          return switch (e.state()) {
            case COMMITTED -> {
              JournalSteps.to(uow, e, TxState.CLAIM_PENDING, e.detail() + ";handover-started");
              yield HandoverStatus.STARTED;
            }
            case CLAIM_PENDING -> HandoverStatus.ALREADY_STARTED;
            default -> HandoverStatus.NOT_ALLOWED;
          };
        });
  }

  /**
   * Schliesst die Reservierung ab.
   *
   * @param transferred tatsaechlich ins Inventar gelegte Menge; 0 gibt das Paket wieder frei
   */
  public SettleStatus settle(UUID journalId, int transferred) {
    SettleStatus status = TxRunner.run(storage, uow -> settleTx(uow, journalId, transferred));
    if (status == SettleStatus.CONFLICT_RELEASED
        || status == SettleStatus.CONFLICT_UNDER_REVIEW
        || status == SettleStatus.INVALID_STATE) {
      audit.adminWarning(
          "OrderHub "
              + type
              + ": settle("
              + transferred
              + ") fuer "
              + journalId
              + " abgelehnt: "
              + status
              + ". Moegliche Inkonsistenz, manuell pruefen.");
    }
    return status;
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
      case COMMITTED:
        // Uebergabe nie begonnen: nur die Freigabe (0) ist zulaessig, eine Menge waere Protokollbruch.
        if (transferred != 0) {
          return SettleStatus.INVALID_STATE;
        }
        break;
      case CLAIM_COMPLETED:
        return SettleStatus.ALREADY_SETTLED;
      case DELIVERY_STORED:
        return transferred == 0 ? SettleStatus.ALREADY_RELEASED : SettleStatus.CONFLICT_RELEASED;
      case MANUAL_REVIEW:
        return transferred == 0 ? SettleStatus.INVALID_STATE : SettleStatus.CONFLICT_UNDER_REVIEW;
      default:
        return SettleStatus.INVALID_STATE;
    }
    if (transferred < 0 || transferred > e.quantity()) {
      return SettleStatus.INVALID_AMOUNT;
    }
    Long parcelId = JournalSteps.numberFromDetail(e.detail(), "parcel");
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

  /**
   * Behandelt seit {@code staleBefore} (Millis) unveraenderte Reservierungen. Nur Eintraege im
   * Zustand COMMITTED (belegbar keine Uebergabe) werden freigegeben. CLAIM_PENDING (Uebergabe
   * begonnen, Ausgang unbekannt) geht nach MANUAL_REVIEW, das Paket bleibt RESERVED. Reservierungen
   * ohne Journal werden nur gemeldet. Nie automatisch freigeben: sonst Item-Dupe.
   */
  public HangingResult releaseHanging(long staleBefore) {
    int released = 0;
    List<RecoveryReport.Escalation> escalations = new ArrayList<>();
    Set<UUID> reported = new HashSet<>();
    int progress;
    do {
      progress = 0;
      List<ParcelRepository.Parcel> reserved =
          TxRunner.run(storage, uow -> repo.apply(uow).findReserved(1000));
      for (ParcelRepository.Parcel p : reserved) {
        if (p.updatedAt() > staleBefore || p.reservedBy() == null) {
          continue;
        }
        Optional<JournalEntry> journal =
            TxRunner.run(storage, uow -> uow.journal().find(p.reservedBy()));
        if (journal.isEmpty()) {
          if (reported.add(p.reservedBy())) {
            escalations.add(
                new RecoveryReport.Escalation(
                    p.reservedBy(), type, p.owner(), p.quantity(), "reservation without journal"));
          }
          continue;
        }
        JournalEntry j = journal.get();
        if (j.state() == TxState.COMMITTED) {
          boolean done =
              TxRunner.run(
                  storage,
                  uow -> {
                    JournalEntry cur = uow.journal().find(j.id()).orElseThrow();
                    ParcelRepository.Parcel fresh = repo.apply(uow).find(p.id()).orElse(null);
                    if (cur.state() != TxState.COMMITTED
                        || cur.updatedAt() > staleBefore
                        || fresh == null
                        || fresh.state() != ParcelRepository.State.RESERVED
                        || !j.id().equals(fresh.reservedBy())
                        || fresh.updatedAt() > staleBefore
                        || !repo.apply(uow).releaseReservation(p.id(), j.id(), uow.now())) {
                      return false;
                    }
                    JournalSteps.to(
                        uow, cur, TxState.DELIVERY_STORED, "parcel=" + p.id() + ";recovered");
                    return true;
                  });
          if (done) {
            released++;
            progress++;
          }
        } else if (j.state() == TxState.CLAIM_PENDING) {
          boolean escalated =
              TxRunner.run(
                  storage,
                  uow -> {
                    JournalEntry cur = uow.journal().find(j.id()).orElseThrow();
                    if (cur.state() != TxState.CLAIM_PENDING || cur.updatedAt() > staleBefore) {
                      return false;
                    }
                    JournalSteps.to(
                        uow,
                        cur,
                        TxState.MANUAL_REVIEW,
                        "parcel=" + p.id() + ";handover outcome unknown");
                    return true;
                  });
          if (escalated) {
            escalations.add(
                new RecoveryReport.Escalation(
                    j.id(),
                    type,
                    p.owner(),
                    j.quantity(),
                    "handover started, outcome unknown; parcel " + p.id() + " stays RESERVED"));
            progress++;
          }
        }
        // MANUAL_REVIEW: bereits eskaliert, Paket bleibt RESERVED, keine erneute Meldung.
      }
    } while (progress > 0);
    return new HangingResult(released, escalations);
  }
}
