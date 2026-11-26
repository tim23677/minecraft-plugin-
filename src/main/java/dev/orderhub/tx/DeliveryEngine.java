package dev.orderhub.tx;

import dev.orderhub.config.Fees;
import dev.orderhub.domain.Order;
import dev.orderhub.money.Money;
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
      boolean taxExempt) {
    public PrepareRequest {
      Objects.requireNonNull(supplier, "supplier");
      Objects.requireNonNull(nonce, "nonce");
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

  private final Storage storage;
  private final Fees fees;

  public DeliveryEngine(Storage storage, Fees fees) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.fees = Objects.requireNonNull(fees, "fees");
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
    long gross = Money.multiply(order.unitPrice(), r.quantity());
    long net = gross - fees.salesTax(gross, r.taxExempt());
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
    return TxRunner.run(storage, uow -> commitTx(uow, journalId));
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

  /** Legt den Return fuer den Lieferanten an und setzt das Journal auf FAILED. */
  static JournalEntry failWithReturn(UnitOfWork uow, JournalEntry entry, String reason) {
    uow.returns()
        .add(
            entry.playerId(),
            entry.orderId(),
            entry.item(),
            entry.quantity(),
            entry.id(),
            uow.now());
    return JournalSteps.to(uow, entry, TxState.FAILED, reason);
  }
}
