package dev.orderhub.service;

import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.storage.AuditRepository;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.JournalSteps;
import dev.orderhub.tx.RecoveryReport;
import dev.orderhub.tx.RecoveryService;
import dev.orderhub.tx.TxRunner;
import dev.orderhub.tx.TxState;
import dev.orderhub.tx.TxType;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Verwaltungsfunktionen. Keine Operation ueberschreibt Datenbankwerte direkt: Stornos laufen ueber
 * {@link OrderService}, Recovery ueber {@link RecoveryService}, jede Aktion wird mit Begruendung im
 * Audit-Log vermerkt. Blockiert, deshalb nie auf einem Minecraft-Thread aufrufen.
 */
public final class AdminService {

  /** Aktor fuer Aktionen ohne Spieler (Konsole). */
  public static final UUID SYSTEM = new UUID(0L, 0L);

  /** Kennzahlen fuer {@code /orderadmin statistics}. */
  public record Statistics(
      long active, long paused, long completed, long cancelled, long expired, int manualReview) {}

  private final Storage storage;
  private final OrderService orders;
  private final RecoveryService recovery;

  public AdminService(Storage storage, OrderService orders, RecoveryService recovery) {
    this.storage = storage;
    this.orders = orders;
    this.recovery = recovery;
  }

  public List<Order> list(OrderQuery query) {
    return storage.transact(uow -> uow.orders().search(query)).join();
  }

  public Optional<Order> order(long id) {
    return storage.transact(uow -> uow.orders().find(id)).join();
  }

  /** Storniert einen Auftrag eines beliebigen Spielers. {@code reason} ist Pflicht. */
  public OrderService.ActionResult cancel(long orderId, String actor, String reason) {
    requireReason(reason);
    OrderService.ActionResult result = orders.cancelOrder(orderId, SYSTEM, true);
    audit(actor, "order.cancel", String.valueOf(orderId), reason, result.status().name());
    return result;
  }

  /** Verarbeitet abgelaufene Auftraege sofort. */
  public OrderService.ExpiryReport purgeExpired(String actor, String reason) {
    requireReason(reason);
    OrderService.ExpiryReport report = orders.expireDue(200);
    audit(
        actor,
        "order.purge-expired",
        null,
        reason,
        "expired="
            + report.expired()
            + " renewed="
            + report.renewed()
            + " errors="
            + report.errors());
    return report;
  }

  public RecoveryReport runRecovery(String actor, String reason) {
    requireReason(reason);
    RecoveryReport report = recovery.sweep();
    audit(actor, "recovery.run", null, reason, report.summary());
    return report;
  }

  public List<JournalEntry> recentTransactions(int offset, int limit) {
    return storage.transact(uow -> uow.journal().findRecent(offset, limit)).join();
  }

  public List<JournalEntry> transactionsOfPlayer(UUID player, int offset, int limit) {
    return storage.transact(uow -> uow.journal().findByPlayer(player, offset, limit)).join();
  }

  public Optional<JournalEntry> transaction(UUID id) {
    return storage.transact(uow -> uow.journal().find(id)).join();
  }

  public List<LedgerRepository.Entry> ledgerOf(UUID tx) {
    return storage.transact(uow -> uow.ledger().findByTx(tx)).join();
  }

  public List<JournalEntry> manualReview(int limit) {
    return storage
        .transact(uow -> uow.journal().findByStates(EnumSet.of(TxState.MANUAL_REVIEW), limit))
        .join();
  }

  /** Ausgang der manuellen Pruefung eines Eintrags in MANUAL_REVIEW. */
  public enum Resolution {
    /** Die Zahlung bzw. Uebergabe ist tatsaechlich erfolgt. */
    PAID,
    /** Nichts wurde gezahlt: der Betrag geht ueber die Refund-Queue an den Spieler. */
    REFUND,
    /** Vorgang verwerfen, es fliesst nichts. */
    DISCARD
  }

  /** Status von {@link #resolveReview}. */
  public enum ResolveStatus {
    RESOLVED,
    ALREADY_RESOLVED,
    NOT_FOUND,
    NOT_IN_REVIEW,
    /** Der Ausgang passt nicht zur Art des Eintrags. */
    UNSUPPORTED,
    /** Der Eintrag wurde bereits mit einem anderen Ausgang aufgeloest. */
    CONFLICT
  }

  /** Ergebnis; {@code refundTx} ist bei REFUND die Id des neuen Rueckerstattungs-Eintrags. */
  public record ResolveResult(ResolveStatus status, UUID refundTx) {}

  private static final String RESOLVED_PREFIX = "resolved:";

  /**
   * Loest einen MANUAL_REVIEW-Eintrag auf. Journal-Uebergang, Queue-Zeile und Audit-Eintrag liegen
   * in derselben Transaktion. Wiederholung mit demselben Ausgang aendert nichts und meldet
   * ALREADY_RESOLVED. Der Ausgang REFUND setzt voraus, dass die Administration geprueft hat, dass
   * nichts gezahlt wurde.
   */
  public ResolveResult resolveReview(UUID txId, Resolution how, String actor, String reason) {
    requireReason(reason);
    return TxRunner.run(
        storage,
        uow -> {
          Optional<JournalEntry> found = uow.journal().find(txId);
          if (found.isEmpty()) {
            return new ResolveResult(ResolveStatus.NOT_FOUND, null);
          }
          JournalEntry e = found.get();
          String marker = RESOLVED_PREFIX + how.name().toLowerCase(java.util.Locale.ROOT);
          if (e.state() != TxState.MANUAL_REVIEW) {
            String detail = e.detail() == null ? "" : e.detail();
            if (detail.startsWith(RESOLVED_PREFIX)) {
              return new ResolveResult(
                  detail.startsWith(marker)
                      ? ResolveStatus.ALREADY_RESOLVED
                      : ResolveStatus.CONFLICT,
                  null);
            }
            return new ResolveResult(ResolveStatus.NOT_IN_REVIEW, null);
          }
          long now = uow.now();
          UUID refundTx = null;
          switch (how) {
            case PAID -> {
              TxState done;
              PayoutRepository.Kind kind;
              switch (e.type()) {
                case DELIVERY -> {
                  done = TxState.PAYOUT_COMPLETED;
                  kind = PayoutRepository.Kind.PAYOUT;
                }
                case CANCEL_REFUND, EXPIRE_REFUND -> {
                  done = TxState.REFUND_COMPLETED;
                  kind = PayoutRepository.Kind.REFUND;
                }
                case CLAIM, ITEM_RETURN -> {
                  done = TxState.CLAIM_COMPLETED;
                  kind = null;
                }
                default -> {
                  return new ResolveResult(ResolveStatus.UNSUPPORTED, null);
                }
              }
              if (kind != null) {
                uow.payouts().resolveReview(e.id(), kind, PayoutRepository.State.COMPLETED, now);
              }
              JournalSteps.to(uow, e, done, marker);
            }
            case DISCARD -> {
              for (PayoutRepository.Kind kind : PayoutRepository.Kind.values()) {
                uow.payouts().resolveReview(e.id(), kind, PayoutRepository.State.FAILED, now);
              }
              JournalSteps.to(uow, e, TxState.FAILED, marker);
            }
            case REFUND -> {
              if ((e.type() != TxType.DELIVERY && e.type() != TxType.ESCROW_CREATE)
                  || e.amount() <= 0) {
                return new ResolveResult(ResolveStatus.UNSUPPORTED, null);
              }
              for (PayoutRepository.Kind kind : PayoutRepository.Kind.values()) {
                uow.payouts().resolveReview(e.id(), kind, PayoutRepository.State.FAILED, now);
              }
              JournalEntry refund =
                  uow.journal()
                      .insertIfAbsent(
                          JournalEntry.created(
                              "review-refund:" + e.id(),
                              e.orderId(),
                              e.playerId(),
                              storage.serverId(),
                              TxType.CANCEL_REFUND,
                              e.item(),
                              e.quantity(),
                              e.amount(),
                              now))
                      .entry();
              uow.ledger()
                  .append(
                      new LedgerRepository.Entry(
                          UUID.randomUUID(),
                          refund.id(),
                          e.playerId(),
                          LedgerRepository.Kind.REFUND,
                          e.amount(),
                          now,
                          "review of " + e.id()));
              uow.payouts()
                  .enqueue(
                      refund.id(), e.playerId(), PayoutRepository.Kind.REFUND, e.amount(), now);
              JournalSteps.chain(
                  uow,
                  refund,
                  "review of " + e.id(),
                  TxState.PREPARED,
                  TxState.COMMITTED,
                  TxState.REFUND_PENDING);
              JournalSteps.to(uow, e, TxState.FAILED, marker + ";refund-tx=" + refund.id());
              refundTx = refund.id();
            }
          }
          uow.audit()
              .append(
                  new AuditRepository.Entry(
                      actor,
                      "review.resolve",
                      e.id().toString(),
                      reason,
                      how.name() + (refundTx == null ? "" : " refund-tx=" + refundTx),
                      storage.serverId(),
                      now));
          return new ResolveResult(ResolveStatus.RESOLVED, refundTx);
        });
  }

  public Statistics statistics() {
    return storage
        .transact(uow ->
            new Statistics(
                count(uow, OrderStatus.ACTIVE),
                count(uow, OrderStatus.PAUSED),
                count(uow, OrderStatus.COMPLETED),
                count(uow, OrderStatus.CANCELLED),
                count(uow, OrderStatus.EXPIRED),
                uow.journal().findByStates(EnumSet.of(TxState.MANUAL_REVIEW), 1000).size()))
        .join();
  }

  public List<AuditRepository.Entry> recentAudit(int limit) {
    return storage.transact(uow -> uow.audit().recent(limit)).join();
  }

  private static long count(dev.orderhub.storage.UnitOfWork uow, OrderStatus status) {
    return uow.orders().count(new OrderQuery(null, null, null, status, null, 0, 1));
  }

  private void audit(String actor, String action, String target, String reason, String detail) {
    storage
        .transact(uow -> {
          uow.audit()
              .append(
                  new AuditRepository.Entry(
                      actor, action, target, reason, detail, storage.serverId(), uow.now()));
          return null;
        })
        .join();
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason required");
    }
  }
}
