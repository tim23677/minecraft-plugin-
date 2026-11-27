package dev.orderhub.storage;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;

/** Repositories auf einer Verbindung, gueltig bis zum Ende der Transaktion. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "Die Repositories sind der Zweck dieser Klasse und an die Transaktion gebunden")
final class JdbcUnitOfWork implements UnitOfWork {

  private final long now;
  private final JdbcOrderRepository orders;
  private final JdbcJournalRepository journal;
  private final JdbcLedgerRepository ledger;
  private final JdbcPayoutRepository payouts;
  private final JdbcParcelRepository claims;
  private final JdbcParcelRepository returns;
  private final JdbcAuditRepository audit;
  private final JdbcHoldRepository holds;
  private final JdbcInsightsRepository insights;
  private final JdbcOutboxRepository outbox;
  private final JdbcTemplateRepository templates;

  JdbcUnitOfWork(Connection connection, Dialect dialect, long now) {
    this.now = now;
    Jdbc jdbc = new Jdbc(connection, dialect);
    this.orders = new JdbcOrderRepository(jdbc);
    this.journal = new JdbcJournalRepository(jdbc);
    this.ledger = new JdbcLedgerRepository(jdbc);
    this.payouts = new JdbcPayoutRepository(jdbc);
    this.claims = new JdbcParcelRepository(jdbc, "item_claims");
    this.returns = new JdbcParcelRepository(jdbc, "item_returns");
    this.audit = new JdbcAuditRepository(jdbc);
    this.holds = new JdbcHoldRepository(jdbc);
    this.insights = new JdbcInsightsRepository(jdbc);
    this.outbox = new JdbcOutboxRepository(jdbc);
    this.templates = new JdbcTemplateRepository(jdbc);
  }

  @Override
  public OrderRepository orders() {
    return orders;
  }

  @Override
  public JournalRepository journal() {
    return journal;
  }

  @Override
  public LedgerRepository ledger() {
    return ledger;
  }

  @Override
  public PayoutRepository payouts() {
    return payouts;
  }

  @Override
  public ParcelRepository claims() {
    return claims;
  }

  @Override
  public ParcelRepository returns() {
    return returns;
  }

  @Override
  public HoldRepository holds() {
    return holds;
  }

  @Override
  public AuditRepository audit() {
    return audit;
  }

  @Override
  public InsightsRepository insights() {
    return insights;
  }

  @Override
  public OutboxRepository outbox() {
    return outbox;
  }

  @Override
  public TemplateRepository templates() {
    return templates;
  }

  @Override
  public long now() {
    return now;
  }
}
