package dev.orderhub.tx;

import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;

/** Abholung gelieferter Items durch den Auftraggeber ({@code item_claims}). */
public final class ClaimEngine extends ParcelTransferEngine {

  public ClaimEngine(Storage storage) {
    this(storage, AuditSink.NOOP);
  }

  public ClaimEngine(Storage storage, AuditSink audit) {
    super(storage, UnitOfWork::claims, TxType.CLAIM, "claim:", audit);
  }
}
