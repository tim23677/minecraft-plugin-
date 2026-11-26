package dev.orderhub.storage;

/** Repositories, gebunden an eine laufende Transaktion. Nur innerhalb von transact() benutzen. */
public interface UnitOfWork {
  OrderRepository orders();

  JournalRepository journal();

  LedgerRepository ledger();

  PayoutRepository payouts();

  ParcelRepository claims();

  ParcelRepository returns();

  AuditRepository audit();

  /** Statistik, Handelsverlauf, Favoriten, Benachrichtigungen. */
  default InsightsRepository insights() {
    throw new UnsupportedOperationException("insights not available");
  }

  /** Discord- und Cache-Outbox. */
  default OutboxRepository outbox() {
    throw new UnsupportedOperationException("outbox not available");
  }

  /** Aktuelle Zeit in Millisekunden, einheitlich fuer die Transaktion. */
  long now();
}
