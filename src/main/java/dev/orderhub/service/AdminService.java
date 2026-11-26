package dev.orderhub.service;

import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.storage.AuditRepository;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.RecoveryReport;
import dev.orderhub.tx.RecoveryService;
import dev.orderhub.tx.TxState;
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
