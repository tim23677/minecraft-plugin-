package dev.orderhub.service;

import dev.orderhub.config.Expiry;
import dev.orderhub.config.Fees;
import dev.orderhub.config.Limits;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.NewOrder;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.economy.EconomyBridge;
import dev.orderhub.economy.EconomyResult;
import dev.orderhub.money.Money;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.JournalSteps;
import dev.orderhub.tx.TxRunner;
import dev.orderhub.tx.TxState;
import dev.orderhub.tx.TxType;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lebenszyklus der Auftraege: erstellen (mit Escrow), stornieren, pausieren, ablaufen lassen,
 * archivieren. Alle Methoden blockieren und gehoeren auf einen Worker-Thread. Economy-Aufrufe
 * stehen nie in einer DB-Transaktion. Wird nach erfolgreicher Abbuchung der Auftrag nicht angelegt,
 * geht das Geld ueber die Refund-Queue zurueck oder das Journal bleibt fuer die manuelle Pruefung
 * stehen; es geht nie Geld verloren.
 */
public final class OrderService {

  /** Eingabe fuer {@link #createOrder}. */
  public record CreateOrderRequest(
      UUID requestId,
      UUID owner,
      ItemSnapshot item,
      long unitPrice,
      int quantity,
      int minDelivery,
      Duration lifetime,
      boolean autoRenew,
      String category,
      int maxOpenOrders,
      boolean feeExempt) {
    public CreateOrderRequest {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(item, "item");
      Objects.requireNonNull(category, "category");
    }
  }

  /** Ausgang von createOrder. */
  public enum CreateStatus {
    CREATED,
    /** Wiederholung derselben Anfrage, der Auftrag existiert bereits. */
    ALREADY_CREATED,
    /** Dieselbe Anfrage laeuft noch oder wartet auf Pruefung; es wurde nichts erneut abgebucht. */
    IN_PROGRESS,
    INVALID_PRICE,
    INVALID_QUANTITY,
    INVALID_AMOUNT,
    LIMIT_REACHED,
    INSUFFICIENT_FUNDS,
    ECONOMY_UNAVAILABLE,
    ECONOMY_FAILED,
    /** Abbuchung mit unklarem Ausgang, MANUAL_REVIEW. */
    ECONOMY_UNCERTAIN,
    /** Auftrag nicht angelegt, abgebuchtes Geld ist in der Refund-Queue. */
    FAILED_REFUND_QUEUED,
    /** Auftrag nicht angelegt, Geld abgebucht, Zustand unklar; bleibt im Journal fuer Recovery. */
    FAILED_NEEDS_REVIEW,
    /** Nichts passiert, Datenbank nicht erreichbar. */
    DB_UNAVAILABLE
  }

  /**
   * @param orderId Id bei CREATED/ALREADY_CREATED
   * @param journalId Journal-Eintrag, soweit angelegt
   */
  public record CreateResult(
      CreateStatus status, Long orderId, UUID journalId, long escrow, long fee) {
    public boolean success() {
      return status == CreateStatus.CREATED || status == CreateStatus.ALREADY_CREATED;
    }
  }

  /** Ausgang der Verwaltungsoperationen. */
  public enum ActionStatus {
    OK,
    ALREADY_DONE,
    NOT_FOUND,
    NOT_OWNER,
    INVALID_STATE,
    CONFLICT
  }

  /**
   * @param refunded in die Refund-Queue gestellter Betrag
   */
  public record ActionResult(ActionStatus status, long refunded, UUID journalId) {
    public boolean ok() {
      return status == ActionStatus.OK;
    }
  }

  /** Ergebnis von expireDue. */
  public record ExpiryReport(int renewed, int expired, int errors) {}

  private static final int COMPENSATION_ATTEMPTS = 3;

  private final Storage storage;
  private final EconomyBridge economy;
  private final Limits limits;
  private final Fees fees;
  private final Expiry expiry;

  public OrderService(
      Storage storage, EconomyBridge economy, Limits limits, Fees fees, Expiry expiry) {
    this.storage = storage;
    this.economy = economy;
    this.limits = limits;
    this.fees = fees;
    this.expiry = expiry;
  }

  // ---------------------------------------------------------------- createOrder

  private record Phase1(CreateResult early, JournalEntry entry) {}

  public CreateResult createOrder(CreateOrderRequest r) {
    CreateStatus invalid = validate(r);
    if (invalid != null) {
      return new CreateResult(invalid, null, null, 0, 0);
    }
    long escrow;
    long fee;
    long total;
    try {
      escrow = Money.multiply(r.unitPrice(), r.quantity());
      fee = fees.createFee(escrow, r.feeExempt());
      total = Money.add(escrow, fee);
    } catch (ArithmeticException e) {
      return new CreateResult(CreateStatus.INVALID_AMOUNT, null, null, 0, 0);
    }
    String key = "escrow:" + r.owner() + ":" + r.requestId();
    Phase1 p1;
    try {
      p1 = TxRunner.run(storage, uow -> phase1(uow, r, key, total, escrow, fee));
    } catch (RuntimeException e) {
      return new CreateResult(CreateStatus.DB_UNAVAILABLE, null, null, escrow, fee);
    }
    if (p1.early() != null) {
      return p1.early();
    }
    JournalEntry entry = p1.entry();

    EconomyResult withdrawn;
    try {
      withdrawn = economy.withdraw(r.owner(), total, "escrow:" + entry.id());
    } catch (RuntimeException e) {
      markReview(entry, "withdraw outcome unknown");
      return new CreateResult(CreateStatus.ECONOMY_UNCERTAIN, null, entry.id(), escrow, fee);
    }
    if (withdrawn != EconomyResult.OK) {
      failJournal(entry, "withdraw " + withdrawn);
      CreateStatus status =
          switch (withdrawn) {
            case INSUFFICIENT -> CreateStatus.INSUFFICIENT_FUNDS;
            case UNAVAILABLE -> CreateStatus.ECONOMY_UNAVAILABLE;
            default -> CreateStatus.ECONOMY_FAILED;
          };
      return new CreateResult(status, null, entry.id(), escrow, fee);
    }

    // Geld ist abgebucht. Ab hier darf nichts mehr verloren gehen.
    try {
      return TxRunner.run(storage, uow -> phase3(uow, r, entry, escrow, fee, total));
    } catch (RuntimeException e) {
      return resolveAfterFailure(entry, escrow, fee, total);
    }
  }

  private CreateStatus validate(CreateOrderRequest r) {
    if (r.unitPrice() < limits.minPrice() || r.unitPrice() > limits.maxPrice()) {
      return CreateStatus.INVALID_PRICE;
    }
    if (r.quantity() < 1 || r.quantity() > limits.maxQuantity()) {
      return CreateStatus.INVALID_QUANTITY;
    }
    if (r.minDelivery() < 1 || r.minDelivery() > r.quantity()) {
      return CreateStatus.INVALID_QUANTITY;
    }
    if (r.maxOpenOrders() < 1) {
      return CreateStatus.LIMIT_REACHED;
    }
    return null;
  }

  private Phase1 phase1(
      UnitOfWork uow, CreateOrderRequest r, String key, long total, long escrow, long fee) {
    Optional<JournalEntry> existing = uow.journal().findByKey(key);
    if (existing.isPresent()) {
      return new Phase1(replay(existing.get(), escrow, fee), null);
    }
    uow.orders().lockOwner(r.owner());
    if (uow.orders().countOpenByOwner(r.owner()) >= r.maxOpenOrders()) {
      return new Phase1(
          new CreateResult(CreateStatus.LIMIT_REACHED, null, null, escrow, fee), null);
    }
    JournalEntry created =
        JournalEntry.created(
            key,
            null,
            r.owner(),
            storage.serverId(),
            TxType.ESCROW_CREATE,
            r.item(),
            r.quantity(),
            total,
            uow.now());
    var inserted = uow.journal().insertIfAbsent(created);
    if (!inserted.created()) {
      return new Phase1(replay(inserted.entry(), escrow, fee), null);
    }
    JournalEntry prepared =
        JournalSteps.to(uow, inserted.entry(), TxState.PREPARED, "withdraw in flight");
    return new Phase1(null, prepared);
  }

  private static CreateResult replay(JournalEntry e, long escrow, long fee) {
    CreateStatus status;
    Long orderId = null;
    switch (e.state()) {
      case COMMITTED:
        orderId = JournalSteps.numberFromDetail(e.detail(), "order");
        status = CreateStatus.ALREADY_CREATED;
        break;
      case REFUND_PENDING, REFUND_COMPLETED:
        status = CreateStatus.FAILED_REFUND_QUEUED;
        break;
      case FAILED:
        status = CreateStatus.ECONOMY_FAILED;
        break;
      case MANUAL_REVIEW:
        status = CreateStatus.FAILED_NEEDS_REVIEW;
        break;
      default:
        status = CreateStatus.IN_PROGRESS;
        break;
    }
    return new CreateResult(status, orderId, e.id(), escrow, fee);
  }

  private CreateResult phase3(
      UnitOfWork uow, CreateOrderRequest r, JournalEntry entry, long escrow, long fee, long total) {
    JournalEntry current = uow.journal().find(entry.id()).orElseThrow();
    if (current.state() != TxState.PREPARED) {
      return replay(current, escrow, fee);
    }
    uow.orders().lockOwner(r.owner());
    if (uow.orders().countOpenByOwner(r.owner()) >= r.maxOpenOrders()) {
      compensate(uow, current, total, "limit reached");
      return new CreateResult(CreateStatus.FAILED_REFUND_QUEUED, null, entry.id(), escrow, fee);
    }
    long now = uow.now();
    Duration lifetime = r.lifetime() == null ? expiry.defaultLifetime() : r.lifetime();
    Order order =
        uow.orders()
            .insert(
                new NewOrder(
                    r.owner(),
                    storage.serverId(),
                    r.item(),
                    r.unitPrice(),
                    r.quantity(),
                    r.minDelivery(),
                    now + lifetime.toMillis(),
                    r.autoRenew() && expiry.autoRenewEnabled(),
                    r.category()),
                now);
    uow.ledger()
        .append(
            new LedgerRepository.Entry(
                UUID.randomUUID(),
                entry.id(),
                r.owner(),
                LedgerRepository.Kind.ESCROW_RESERVE,
                escrow,
                now,
                "order=" + order.id()));
    if (fee > 0) {
      uow.ledger()
          .append(
              new LedgerRepository.Entry(
                  UUID.randomUUID(),
                  entry.id(),
                  r.owner(),
                  LedgerRepository.Kind.CREATE_FEE,
                  fee,
                  now,
                  "order=" + order.id()));
    }
    JournalSteps.to(uow, current, TxState.COMMITTED, "order=" + order.id());
    return new CreateResult(CreateStatus.CREATED, order.id(), entry.id(), escrow, fee);
  }

  /** Erstattet den abgebuchten Betrag ueber die Refund-Queue (innerhalb einer Transaktion). */
  private void compensate(UnitOfWork uow, JournalEntry current, long total, String reason) {
    long now = uow.now();
    uow.ledger()
        .append(
            new LedgerRepository.Entry(
                UUID.randomUUID(),
                current.id(),
                current.playerId(),
                LedgerRepository.Kind.FAILED,
                total,
                now,
                reason));
    uow.ledger()
        .append(
            new LedgerRepository.Entry(
                UUID.randomUUID(),
                current.id(),
                current.playerId(),
                LedgerRepository.Kind.REVERSAL,
                total,
                now,
                reason));
    uow.payouts()
        .enqueue(current.id(), current.playerId(), PayoutRepository.Kind.REFUND, total, now);
    JournalSteps.chain(uow, current, reason, TxState.COMMITTED, TxState.REFUND_PENDING);
  }

  /**
   * Die Erstell-Transaktion ist fehlgeschlagen oder ihr Ausgang ist unbekannt. Der Journal-CAS
   * PREPARED nach COMMITTED entscheidet exklusiv: entweder wurde der Auftrag angelegt oder die
   * Erstattung wird eingestellt, nie beides.
   */
  private CreateResult resolveAfterFailure(JournalEntry entry, long escrow, long fee, long total) {
    for (int attempt = 1; attempt <= COMPENSATION_ATTEMPTS; attempt++) {
      try {
        return TxRunner.run(
            storage,
            uow -> {
              JournalEntry current = uow.journal().find(entry.id()).orElseThrow();
              if (current.state() == TxState.PREPARED) {
                compensate(uow, current, total, "order creation failed");
                return new CreateResult(
                    CreateStatus.FAILED_REFUND_QUEUED, null, entry.id(), escrow, fee);
              }
              return replay(current, escrow, fee);
            });
      } catch (RuntimeException e) {
        try {
          Thread.sleep(25L * attempt);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    return new CreateResult(CreateStatus.FAILED_NEEDS_REVIEW, null, entry.id(), escrow, fee);
  }

  private void failJournal(JournalEntry entry, String detail) {
    try {
      TxRunner.run(
          storage,
          uow -> {
            JournalEntry current = uow.journal().find(entry.id()).orElseThrow();
            if (current.state() == TxState.PREPARED) {
              JournalSteps.to(uow, current, TxState.FAILED, detail);
            }
            return null;
          });
    } catch (RuntimeException e) {
      // bleibt PREPARED, die Recovery eskaliert (Abbuchung kann nicht ausgeschlossen werden)
    }
  }

  private void markReview(JournalEntry entry, String detail) {
    try {
      TxRunner.run(
          storage,
          uow -> {
            JournalEntry current = uow.journal().find(entry.id()).orElseThrow();
            if (current.state().canTransitionTo(TxState.MANUAL_REVIEW)) {
              JournalSteps.to(uow, current, TxState.MANUAL_REVIEW, detail);
            }
            return null;
          });
    } catch (RuntimeException e) {
      // bleibt PREPARED, die Recovery eskaliert
    }
  }

  // ---------------------------------------------------------------- Verwaltung

  /** Storniert den Auftrag und stellt den Restescrow in die Refund-Queue. */
  public ActionResult cancelOrder(long orderId, UUID actor, boolean admin) {
    return TxRunner.run(
        storage,
        uow ->
            terminateWithRefund(
                uow,
                orderId,
                actor,
                admin,
                OrderStatus.CANCELLED,
                TxType.CANCEL_REFUND,
                "cancel:"));
  }

  private ActionResult terminateWithRefund(
      UnitOfWork uow,
      long orderId,
      UUID actor,
      boolean admin,
      OrderStatus target,
      TxType type,
      String keyPrefix) {
    Optional<Order> found = uow.orders().findForUpdate(orderId);
    if (found.isEmpty()) {
      return new ActionResult(ActionStatus.NOT_FOUND, 0, null);
    }
    Order order = found.get();
    if (!admin && !order.owner().equals(actor)) {
      return new ActionResult(ActionStatus.NOT_OWNER, 0, null);
    }
    String key = keyPrefix + orderId;
    Optional<JournalEntry> existing = uow.journal().findByKey(key);
    if (existing.isPresent()) {
      return new ActionResult(ActionStatus.ALREADY_DONE, 0, existing.get().id());
    }
    if (order.status() != OrderStatus.ACTIVE && order.status() != OrderStatus.PAUSED) {
      return new ActionResult(ActionStatus.INVALID_STATE, 0, null);
    }
    long now = uow.now();
    long refund = order.escrowRemaining();
    if (!uow.orders().update(terminate(order, target, now))) {
      return new ActionResult(ActionStatus.CONFLICT, 0, null);
    }
    JournalEntry created =
        JournalEntry.created(
            key,
            orderId,
            order.owner(),
            storage.serverId(),
            type,
            order.item(),
            order.remaining(),
            refund,
            now);
    var inserted = uow.journal().insertIfAbsent(created);
    if (!inserted.created()) {
      throw new JournalSteps.StaleJournalException(inserted.entry(), TxState.CREATED);
    }
    JournalEntry entry = inserted.entry();
    if (refund > 0) {
      uow.ledger()
          .append(
              new LedgerRepository.Entry(
                  UUID.randomUUID(),
                  entry.id(),
                  order.owner(),
                  LedgerRepository.Kind.REFUND,
                  refund,
                  now,
                  "order=" + orderId));
      uow.payouts().enqueue(entry.id(), order.owner(), PayoutRepository.Kind.REFUND, refund, now);
      JournalSteps.chain(
          uow, entry, "queued", TxState.PREPARED, TxState.COMMITTED, TxState.REFUND_PENDING);
    } else {
      JournalSteps.chain(
          uow,
          entry,
          "nothing to refund",
          TxState.PREPARED,
          TxState.COMMITTED,
          TxState.REFUND_PENDING,
          TxState.REFUND_COMPLETED);
    }
    return new ActionResult(ActionStatus.OK, refund, entry.id());
  }

  private static Order terminate(Order o, OrderStatus status, long now) {
    return new Order(
        o.id(),
        o.owner(),
        o.serverId(),
        o.item(),
        o.unitPrice(),
        o.quantityTotal(),
        o.quantityFilled(),
        o.minDelivery(),
        0L,
        status,
        o.createdAt(),
        o.expiresAt(),
        now,
        o.version() + 1,
        o.autoRenew(),
        o.category());
  }

  private static Order withExpiry(Order o, long expiresAt, long now) {
    return new Order(
        o.id(),
        o.owner(),
        o.serverId(),
        o.item(),
        o.unitPrice(),
        o.quantityTotal(),
        o.quantityFilled(),
        o.minDelivery(),
        o.escrowRemaining(),
        o.status(),
        o.createdAt(),
        expiresAt,
        now,
        o.version() + 1,
        o.autoRenew(),
        o.category());
  }

  public ActionResult pause(long orderId, UUID actor, boolean admin) {
    return changeStatus(orderId, actor, admin, OrderStatus.ACTIVE, OrderStatus.PAUSED);
  }

  public ActionResult resume(long orderId, UUID actor, boolean admin) {
    return changeStatus(orderId, actor, admin, OrderStatus.PAUSED, OrderStatus.ACTIVE);
  }

  /** Archiviert beendete Auftraege (COMPLETED, CANCELLED, EXPIRED). */
  public ActionResult archive(long orderId, UUID actor, boolean admin) {
    return TxRunner.run(
        storage,
        uow -> {
          Optional<Order> found = uow.orders().findForUpdate(orderId);
          if (found.isEmpty()) {
            return new ActionResult(ActionStatus.NOT_FOUND, 0, null);
          }
          Order order = found.get();
          if (!admin && !order.owner().equals(actor)) {
            return new ActionResult(ActionStatus.NOT_OWNER, 0, null);
          }
          if (order.status() == OrderStatus.ARCHIVED) {
            return new ActionResult(ActionStatus.ALREADY_DONE, 0, null);
          }
          if (!order.status().isTerminal() || order.escrowRemaining() != 0) {
            return new ActionResult(ActionStatus.INVALID_STATE, 0, null);
          }
          return uow.orders()
                  .update(
                      order.withVersionAndStatus(
                          order.version() + 1, OrderStatus.ARCHIVED, uow.now()))
              ? new ActionResult(ActionStatus.OK, 0, null)
              : new ActionResult(ActionStatus.CONFLICT, 0, null);
        });
  }

  private ActionResult changeStatus(
      long orderId, UUID actor, boolean admin, OrderStatus from, OrderStatus to) {
    return TxRunner.run(
        storage,
        uow -> {
          Optional<Order> found = uow.orders().findForUpdate(orderId);
          if (found.isEmpty()) {
            return new ActionResult(ActionStatus.NOT_FOUND, 0, null);
          }
          Order order = found.get();
          if (!admin && !order.owner().equals(actor)) {
            return new ActionResult(ActionStatus.NOT_OWNER, 0, null);
          }
          if (order.status() == to) {
            return new ActionResult(ActionStatus.ALREADY_DONE, 0, null);
          }
          if (order.status() != from) {
            return new ActionResult(ActionStatus.INVALID_STATE, 0, null);
          }
          return uow.orders().update(order.withVersionAndStatus(order.version() + 1, to, uow.now()))
              ? new ActionResult(ActionStatus.OK, 0, null)
              : new ActionResult(ActionStatus.CONFLICT, 0, null);
        });
  }

  // ---------------------------------------------------------------- Ablauf

  private enum Expired {
    RENEWED,
    EXPIRED,
    SKIPPED
  }

  /**
   * Verarbeitet abgelaufene Auftraege in Batches. Je Auftrag eine eigene Transaktion. Mit
   * Auto-Verlaengerung und gedecktem Escrow wird verlaengert, sonst abgelaufen und der Restescrow
   * erstattet.
   */
  public ExpiryReport expireDue(int batchSize) {
    int renewed = 0;
    int expired = 0;
    int errors = 0;
    for (int round = 0; round < 1000; round++) {
      List<Order> due =
          TxRunner.run(storage, uow -> uow.orders().findExpired(uow.now(), batchSize));
      if (due.isEmpty()) {
        break;
      }
      int progress = 0;
      for (Order o : due) {
        try {
          Expired result = TxRunner.run(storage, uow -> expireOne(uow, o.id()));
          if (result == Expired.RENEWED) {
            renewed++;
            progress++;
          } else if (result == Expired.EXPIRED) {
            expired++;
            progress++;
          }
        } catch (RuntimeException e) {
          errors++;
        }
      }
      if (progress == 0 || due.size() < batchSize) {
        break;
      }
    }
    return new ExpiryReport(renewed, expired, errors);
  }

  private Expired expireOne(UnitOfWork uow, long orderId) {
    Optional<Order> found = uow.orders().findForUpdate(orderId);
    long now = uow.now();
    if (found.isEmpty()
        || found.get().status() != OrderStatus.ACTIVE
        || found.get().expiresAt() > now) {
      return Expired.SKIPPED;
    }
    Order order = found.get();
    boolean funded =
        order.escrowRemaining() > 0 && order.escrowRemaining() >= order.expectedEscrow();
    if (order.autoRenew() && expiry.autoRenewEnabled() && funded) {
      long next = now + expiry.defaultLifetime().toMillis();
      return uow.orders().update(withExpiry(order, next, now)) ? Expired.RENEWED : Expired.SKIPPED;
    }
    ActionResult result =
        terminateWithRefund(
            uow,
            orderId,
            order.owner(),
            true,
            OrderStatus.EXPIRED,
            TxType.EXPIRE_REFUND,
            "expire:");
    return result.ok() ? Expired.EXPIRED : Expired.SKIPPED;
  }
}
