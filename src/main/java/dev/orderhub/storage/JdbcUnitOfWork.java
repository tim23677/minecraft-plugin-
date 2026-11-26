package dev.orderhub.storage;

import java.sql.Connection;

/** Repositories auf einer Verbindung, gueltig bis zum Ende der Transaktion. */
final class JdbcUnitOfWork implements UnitOfWork {

  private final long now;
  private final JdbcOrderRepository orders;
  private final JdbcJournalRepository journal;
  private final JdbcLedgerRepository ledger;
  private final JdbcPayoutRepository payouts;
  private final JdbcParcelRepository claims;
  private final JdbcParcelRepository returns;

  JdbcUnitOfWork(Connection connection, Dialect dialect, long now) {
    this.now = now;
    Jdbc jdbc = new Jdbc(connection, dialect);
    this.orders = new JdbcOrderRepository(jdbc);
    this.journal = new JdbcJournalRepository(jdbc);
    this.ledger = new JdbcLedgerRepository(jdbc);
    this.payouts = new JdbcPayoutRepository(jdbc);
    this.claims = new JdbcParcelRepository(jdbc, "item_claims");
    this.returns = new JdbcParcelRepository(jdbc, "item_returns");
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
  public long now() {
    return now;
  }
}
