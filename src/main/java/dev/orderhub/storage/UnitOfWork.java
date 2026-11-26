package dev.orderhub.storage;

/** Repositories, gebunden an eine laufende Transaktion. Nur innerhalb von transact() benutzen. */
public interface UnitOfWork {
  OrderRepository orders();

  JournalRepository journal();

  LedgerRepository ledger();

  PayoutRepository payouts();

  ParcelRepository claims();

  ParcelRepository returns();

  /** Aktuelle Zeit in Millisekunden, einheitlich fuer die Transaktion. */
  long now();
}
