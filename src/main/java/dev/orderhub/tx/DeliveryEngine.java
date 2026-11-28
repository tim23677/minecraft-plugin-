package dev.orderhub.tx;

import dev.orderhub.config.Fees;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.Order;
import dev.orderhub.money.Money;
import dev.orderhub.storage.HoldRepository;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Zustandsmaschine einer Lieferung (siehe ARCHITECTURE.md). Alle Methoden blockieren und gehoeren
 * auf einen Worker-Thread. Jede Methode ist idempotent: Wiederholung mit gleichem Schluessel bzw.
 * gleicher Journal-Id hat keine weitere Wirkung.
 */
public final class DeliveryEngine {

  /** Eingabe fuer {@link #prepare(PrepareRequest)}. */
  public record PrepareRequest(
      long orderId,
      UUID supplier,
      int quantity,
      String nonce,
      Long expectedOrderVersion,
      boolean taxExempt,
      int heldQuantity) {
    public PrepareRequest {
      Objects.requireNonNull(supplier, "supplier");
      Objects.requireNonNull(nonce, "nonce");
    }

    /** Ohne Ueberschuss: die Reservierung haelt genau {@code quantity} Items. */
    public PrepareRequest(
        long orderId,
        UUID supplier,
        int quantity,
        String nonce,
        Long expectedOrderVersion,
        boolean taxExempt) {
      this(orderId, supplier, quantity, nonce, expectedOrderVersion, taxExempt, quantity);
    }
  }

  /** Ausgang von prepare. */
  public enum PrepareStatus {
    PREPARED,
    ALREADY_PREPARED,
    KEY_ALREADY_FAILED,
    ORDER_NOT_FOUND,
    ORDER_NOT_ACTIVE,
    SELF_DELIVERY,
    BELOW_MINIMUM,
    EXCEEDS_REMAINING,
    /** Das Tageslimit des Lieferanten ist erreicht. */
    DAILY_LIMIT,
    STALE_ORDER,
    INVALID_QUANTITY
  }

  /**
   * @param journalId Journal-Eintrag bei PREPARED/ALREADY_PREPARED, sonst {@code null}
   * @param netPayout Auszahlung an den Lieferanten nach Steuer
   */
  public record PrepareResult(PrepareStatus status, UUID journalId, int quantity, long netPayout) {
    public boolean ok() {
      return status == PrepareStatus.PREPARED || status == PrepareStatus.ALREADY_PREPARED;
    }
  }

  /** Ausgang von confirmItemsRemoved. */
  public enum ConfirmResult {
    CONFIRMED,
    ALREADY_CONFIRMED,
    NOT_FOUND,
    /**
     * Eintrag ist FAILED oder MANUAL_REVIEW: der Aufrufer behaelt die Items und gibt sie zurueck.
     */
    REJECTED
  }

  /** Ausgang von commit. */
  public enum CommitStatus {
    COMMITTED,
    ALREADY_COMMITTED,
    /** Auftrag nahm die Lieferung nicht mehr an, Items liegen als Return fuer den Lieferanten. */
    REJECTED_ITEMS_RETURNED,
    ALREADY_FAILED,
    NOT_FOUND,
    /** Items noch nicht bestaetigt. */
    NOT_CONFIRMED,
    INVALID_STATE
  }

  /**
   * @param event nur bei COMMITTED gesetzt
   */
  public record CommitResult(CommitStatus status, DeliveryCommitted event, String reason) {}

  /** Ausgang von abort. */
  public enum AbortResult {
    ABORTED,
    ABORTED_ITEMS_RETURNED,
    ALREADY_FAILED,
    NOT_ABORTABLE,
    NOT_FOUND
  }

  private static final long DAY_MILLIS = 24L * 3_600_000L;

  private final Storage storage;
  private final java.util.function.Supplier<Fees> fees;
  private final java.util.function.IntSupplier dailyItems;

  public DeliveryEngine(Storage storage, Fees fees) {
    this(storage, () -> fees);
    Objects.requireNonNull(fees, "fees");
  }

  /** {@code fees} liefert den aktuellen Stand (bei reload ausgetauscht), einmal je Lieferung. */
  public DeliveryEngine(Storage storage, java.util.function.Supplier<Fees> fees) {
    this(storage, fees, () -> 0);
  }

  /** {@code dailyItems} liefert das Tageslimit gelieferter Items je Lieferant, 0 = kein Limit. */
  public DeliveryEngine(
      Storage storage,
      java.util.function.Supplier<Fees> fees,
      java.util.function.IntSupplier dailyItems) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.fees = Objects.requireNonNull(fees, "fees");
    this.dailyItems = Objects.requireNonNull(dailyItems, "dailyItems");
  }

  /** Schluessel einer Lieferung. */
  public static String key(long orderId, UUID supplier, String nonce) {
    return "delivery:" + orderId + ":" + supplier + ":" + nonce;
  }

  public PrepareResult prepare(long orderId, UUID supplier, int quantity, String nonce) {
    return prepare(new PrepareRequest(orderId, supplier, quantity, nonce, null, false));
  }

  /** CREATED, dann PREPARED in einer Transaktion. Keine Mengenaenderung am Auftrag. */
  public PrepareResult prepare(PrepareRequest r) {
    String key = key(r.orderId(), r.supplier(), r.nonce());
    return TxRunner.run(storage, uow -> prepareTx(uow, r, key));
  }

  private PrepareResult prepareTx(UnitOfWork uow, PrepareRequest r, String key) {
    Optional<JournalEntry> existing = uow.journal().findByKey(key);
    if (existing.isPresent()) {
      return replay(existing.get());
    }
    if (r.quantity() < 1) {
      return new PrepareResult(PrepareStatus.INVALID_QUANTITY, null, r.quantity(), 0);
    }
    Optional<Order> found = uow.orders().findForUpdate(r.orderId());
    if (found.isEmpty()) {
      return new PrepareResult(PrepareStatus.ORDER_NOT_FOUND, null, r.quantity(), 0);
    }
    Order order = found.get();
    if (r.expectedOrderVersion() != null && r.expectedOrderVersion() != order.version()) {
      return new PrepareResult(PrepareStatus.STALE_ORDER, null, r.quantity(), 0);
    }
    if (!order.status().acceptsDeliveries()) {
      return new PrepareResult(PrepareStatus.ORDER_NOT_ACTIVE, null, r.quantity(), 0);
    }
    if (order.owner().equals(r.supplier())) {
      return new PrepareResult(PrepareStatus.SELF_DELIVERY, null, r.quantity(), 0);
    }
    if (r.quantity() > order.remaining()) {
      return new PrepareResult(PrepareStatus.EXCEEDS_REMAINING, null, r.quantity(), 0);
    }
    if (r.quantity() < Math.min(order.minDelivery(), order.remaining())) {
      return new PrepareResult(PrepareStatus.BELOW_MINIMUM, null, r.quantity(), 0);
    }
    int dailyLimit = dailyItems.getAsInt();
    if (dailyLimit > 0
        && uow.journal().sumDeliveredSince(r.supplier(), uow.now() - DAY_MILLIS) + r.quantity()
            > dailyLimit) {
      return new PrepareResult(PrepareStatus.DAILY_LIMIT, null, r.quantity(), 0);
    }
    long gross = Money.multiply(order.unitPrice(), r.quantity());
    long net = gross - fees.get().salesTax(gross, r.taxExempt());
    JournalEntry created =
        JournalEntry.created(
            key,
            order.id(),
            r.supplier(),
            storage.serverId(),
            TxType.DELIVERY,
            order.item(),
            r.quantity(),
            net,
            uow.now());
    var inserted = uow.journal().insertIfAbsent(created);
    if (!inserted.created()) {
      return replay(inserted.entry());
    }
    // Rueckgabe-Reservierung im selben Schritt: ab hier gehen die Items im Menue nie verloren.
    uow.holds()
        .add(
            inserted.entry().id(),
            r.supplier(),
            order.id(),
            order.item(),
            Math.max(r.heldQuantity(), r.quantity()),
            uow.now());
    JournalEntry prepared =
        JournalSteps.to(uow, inserted.entry(), TxState.PREPARED, "validated v" + order.version());
    return new PrepareResult(PrepareStatus.PREPARED, prepared.id(), r.quantity(), net);
  }

  private static PrepareResult replay(JournalEntry e) {
    if (e.state() == TxState.FAILED) {
      return new PrepareResult(PrepareStatus.KEY_ALREADY_FAILED, e.id(), e.quantity(), e.amount());
    }
    return new PrepareResult(PrepareStatus.ALREADY_PREPARED, e.id(), e.quantity(), e.amount());
  }

  /** PREPARED nach ITEMS_REMOVED. Aufruf, nachdem der Aufrufer die Items aus dem Menue nahm. */
  public ConfirmResult confirmItemsRemoved(UUID journalId) {
    return TxRunner.run(
        storage,
        uow -> {
          Optional<JournalEntry> found = uow.journal().find(journalId);
          if (found.isEmpty() || found.get().type() != TxType.DELIVERY) {
            return ConfirmResult.NOT_FOUND;
          }
          JournalEntry e = found.get();
          return switch (e.state()) {
            case PREPARED -> {
              JournalSteps.to(uow, e, TxState.ITEMS_REMOVED, "items removed");
              yield ConfirmResult.CONFIRMED;
            }
            case CREATED, FAILED, MANUAL_REVIEW -> ConfirmResult.REJECTED;
            default -> ConfirmResult.ALREADY_CONFIRMED;
          };
        });
  }

  /**
   * Festschreibung in einer Transaktion: Auftrag, Claim-Paket fuer den Auftraggeber, Ledger,
   * Auszahlungs-Queue und Journal. Nimmt der Auftrag die Lieferung nicht mehr an, werden die Items
   * in derselben Transaktion an den Lieferanten zurueckgegeben.
   */
  public CommitResult commit(UUID journalId) {
    CommitResult result = TxRunner.run(storage, uow -> commitTx(uow, journalId));
    DeliveryCommitted event = result.event();
    if (result.status() == CommitStatus.COMMITTED && event != null) {
      // Erst nach dem Festschreiben: eine zurueckgerollte Transaktion hinterlaesst keine Zeile.
      TransactionLog.items(
          "DELIVERY_COMMIT",
          event.journalId(),
          event.supplier(),
          event.itemKey(),
          event.quantity(),
          event.gross(),
          "COMMITTED",
          event.orderId());
    }
    return result;
  }

  private CommitResult commitTx(UnitOfWork uow, UUID journalId) {
    Optional<JournalEntry> found = uow.journal().find(journalId);
    if (found.isEmpty() || found.get().type() != TxType.DELIVERY) {
      return new CommitResult(CommitStatus.NOT_FOUND, null, "unknown journal");
    }
    JournalEntry e = found.get();
    switch (e.state()) {
      case ITEMS_REMOVED:
        break;
      case PREPARED, CREATED:
        return new CommitResult(CommitStatus.NOT_CONFIRMED, null, "items not confirmed");
      case FAILED:
        return new CommitResult(CommitStatus.ALREADY_FAILED, null, e.detail());
      case MANUAL_REVIEW:
        return new CommitResult(CommitStatus.INVALID_STATE, null, "manual review");
      default:
        return new CommitResult(CommitStatus.ALREADY_COMMITTED, null, null);
    }
    long orderId = Objects.requireNonNull(e.orderId(), "delivery without order");
    Optional<Order> found2 = uow.orders().findForUpdate(orderId);
    if (found2.isEmpty()) {
      DeliveryEngine.failWithReturn(uow, e, "order missing");
      return new CommitResult(CommitStatus.REJECTED_ITEMS_RETURNED, null, "order missing");
    }
    Order order = found2.get();
    if (!order.status().acceptsDeliveries() || e.quantity() > order.remaining()) {
      String reason = order.status().acceptsDeliveries() ? "exceeds remaining" : "order not active";
      DeliveryEngine.failWithReturn(uow, e, reason);
      return new CommitResult(CommitStatus.REJECTED_ITEMS_RETURNED, null, reason);
    }
    long now = uow.now();
    Order next = order.afterDelivery(e.quantity(), now);
    if (!uow.orders().update(next)) {
      throw new JournalSteps.StaleJournalException(e, TxState.COMMITTED);
    }
    long gross = Money.multiply(order.unitPrice(), e.quantity());
    long net = e.amount();
    long tax = gross - net;
    if (tax < 0) {
      throw new IllegalStateException("net payout exceeds gross for " + e.id());
    }
    if (net > 0) {
      uow.ledger()
          .append(
              new LedgerRepository.Entry(
                  UUID.randomUUID(),
                  e.id(),
                  e.playerId(),
                  LedgerRepository.Kind.PAYOUT,
                  net,
                  now,
                  "order=" + orderId));
    }
    if (tax > 0) {
      uow.ledger()
          .append(
              new LedgerRepository.Entry(
                  UUID.randomUUID(),
                  e.id(),
                  e.playerId(),
                  LedgerRepository.Kind.SALES_TAX,
                  tax,
                  now,
                  "order=" + orderId));
    }
    uow.claims().add(order.owner(), orderId, order.item(), e.quantity(), e.id(), now);
    settleHoldAfterCommit(uow, e, order.item(), orderId, now);
    JournalEntry committed = JournalSteps.to(uow, e, TxState.COMMITTED, "order=" + orderId);
    if (net > 0) {
      uow.payouts().enqueue(e.id(), e.playerId(), PayoutRepository.Kind.PAYOUT, net, now);
      JournalSteps.to(uow, committed, TxState.PAYOUT_PENDING, "queued");
    } else {
      JournalSteps.chain(
          uow,
          committed,
          "nothing to pay",
          TxState.PAYOUT_PENDING,
          TxState.PAYOUT_IN_PROGRESS,
          TxState.PAYOUT_COMPLETED);
    }
    DeliveryCommitted event =
        new DeliveryCommitted(
            e.id(),
            orderId,
            order.item().itemKey(),
            e.playerId(),
            order.owner(),
            e.quantity(),
            order.unitPrice(),
            gross,
            tax,
            net,
            next.status() == dev.orderhub.domain.OrderStatus.COMPLETED,
            now,
            e.serverId());
    return new CommitResult(CommitStatus.COMMITTED, event, null);
  }

  /** Bricht eine noch nicht festgeschriebene Lieferung ab. Items gehen per Return zurueck. */
  public AbortResult abort(UUID journalId) {
    return TxRunner.run(
        storage,
        uow -> {
          Optional<JournalEntry> found = uow.journal().find(journalId);
          if (found.isEmpty() || found.get().type() != TxType.DELIVERY) {
            return AbortResult.NOT_FOUND;
          }
          JournalEntry e = found.get();
          return switch (e.state()) {
            case CREATED, PREPARED -> {
              // Der Aufrufer gibt die Items selbst zurueck: die Reservierung ist damit verbraucht.
              uow.holds().finish(e.id(), HoldRepository.State.CONSUMED, uow.now());
              JournalSteps.to(uow, e, TxState.FAILED, "aborted before items removed");
              yield AbortResult.ABORTED;
            }
            case ITEMS_REMOVED -> {
              failWithReturn(uow, e, "aborted");
              yield AbortResult.ABORTED_ITEMS_RETURNED;
            }
            case FAILED -> AbortResult.ALREADY_FAILED;
            default -> AbortResult.NOT_ABORTABLE;
          };
        });
  }

  /**
   * Legt den Return fuer den Lieferanten an und setzt das Journal auf FAILED. Gibt es eine
   * Reservierung, geht ihre gesamte Menge (auch der Ueberschuss) zurueck.
   */
  static JournalEntry failWithReturn(UnitOfWork uow, JournalEntry entry, String reason) {
    int quantity = entry.quantity();
    Optional<HoldRepository.Hold> hold = uow.holds().find(entry.id());
    if (hold.isPresent() && hold.get().state() == HoldRepository.State.HELD) {
      quantity = hold.get().quantity();
      uow.holds().finish(entry.id(), HoldRepository.State.RELEASED, uow.now());
    }
    uow.returns()
        .add(entry.playerId(), entry.orderId(), entry.item(), quantity, entry.id(), uow.now());
    return JournalSteps.to(uow, entry, TxState.FAILED, reason);
  }

  /** Verbraucht die Reservierung; Ueberschuss ueber die gelieferte Menge wird zur Rueckgabe. */
  private static void settleHoldAfterCommit(
      UnitOfWork uow, JournalEntry e, ItemSnapshot item, long orderId, long now) {
    Optional<HoldRepository.Hold> hold = uow.holds().find(e.id());
    if (hold.isEmpty() || hold.get().state() != HoldRepository.State.HELD) {
      return;
    }
    int surplus = hold.get().quantity() - e.quantity();
    if (surplus > 0) {
      uow.returns().add(e.playerId(), orderId, item, surplus, e.id(), now);
    }
    uow.holds().finish(e.id(), HoldRepository.State.CONSUMED, now);
  }

  /** Ausgang von {@link #closeAttempt}. */
  public enum AttemptStatus {
    /** Der Versuch existierte nicht und kann nun nie mehr entstehen: Items direkt zurueckgeben. */
    NEVER_PREPARED,
    /** Der Versuch existiert: die Reservierung und die Recovery gehoeren die Items. */
    EXISTS
  }

  /**
   * Schliesst einen Lieferversuch mit unklarem Ausgang von prepare atomar ab: Existiert der
   * Journal-Eintrag, haben Reservierung und Recovery die Items. Sonst wird unter demselben
   * Schluessel ein FAILED-Eintrag (Tombstone) angelegt, so dass ein verspaetet committendes prepare
   * nie mehr eine Reservierung erzeugt. Erst danach darf der Aufrufer Items direkt zurueckgeben.
   * Wirft bei Datenbankfehlern: dann nichts zurueckgeben, spaeter erneut versuchen.
   */
  public AttemptStatus closeAttempt(long orderId, UUID supplier, String nonce) {
    String key = key(orderId, supplier, nonce);
    return TxRunner.run(
        storage,
        uow -> {
          if (uow.journal().findByKey(key).isPresent()) {
            return AttemptStatus.EXISTS;
          }
          JournalEntry tombstone =
              JournalEntry.created(
                  key,
                  orderId,
                  supplier,
                  storage.serverId(),
                  TxType.DELIVERY,
                  null,
                  0,
                  0,
                  uow.now());
          var inserted = uow.journal().insertIfAbsent(tombstone);
          if (!inserted.created()) {
            return AttemptStatus.EXISTS;
          }
          JournalSteps.to(uow, inserted.entry(), TxState.FAILED, "prepare outcome unknown: closed");
          return AttemptStatus.NEVER_PREPARED;
        });
  }

  /** Journal-Eintrag zu einem Lieferversuch (fuer Aufrufer mit unklarem Ausgang von prepare). */
  public Optional<JournalEntry> lookup(long orderId, UUID supplier, String nonce) {
    return TxRunner.run(storage, uow -> uow.journal().findByKey(key(orderId, supplier, nonce)));
  }
}
