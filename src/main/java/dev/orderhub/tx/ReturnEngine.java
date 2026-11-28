package dev.orderhub.tx;

import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;

/**
 * Rueckgabe nicht angenommener oder zurueckgerollter Items an Lieferanten ({@code item_returns}).
 */
public final class ReturnEngine extends ParcelTransferEngine {

  public ReturnEngine(Storage storage) {
    this(storage, AuditSink.NOOP);
  }

  public ReturnEngine(Storage storage, AuditSink audit) {
    super(storage, UnitOfWork::returns, TxType.ITEM_RETURN, "return:", audit);
  }
}
