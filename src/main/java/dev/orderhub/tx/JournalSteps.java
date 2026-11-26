package dev.orderhub.tx;

import dev.orderhub.storage.UnitOfWork;

/** Hilfen fuer Journal-Uebergaenge innerhalb einer Transaktion. */
public final class JournalSteps {

  private JournalSteps() {}

  /** Veraltetes Journal: ein anderer Prozess hat den Eintrag veraendert. Rollt die Tx zurueck. */
  public static final class StaleJournalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StaleJournalException(JournalEntry entry, TxState target) {
      super("stale journal " + entry.id() + " in " + entry.state() + " -> " + target);
    }
  }

  /** Ein CAS-Uebergang. Wirft bei veraltetem Zustand {@link StaleJournalException}. */
  public static JournalEntry to(UnitOfWork uow, JournalEntry current, TxState target, String detail) {
    if (!uow.journal().transition(current, target, detail, uow.now())) {
      throw new StaleJournalException(current, target);
    }
    return uow.journal()
        .find(current.id())
        .orElseThrow(() -> new IllegalStateException("journal vanished " + current.id()));
  }

  /** Mehrere aufeinanderfolgende Uebergaenge, {@code detail} gilt fuer den letzten. */
  public static JournalEntry chain(
      UnitOfWork uow, JournalEntry current, String detail, TxState... targets) {
    JournalEntry entry = current;
    for (int i = 0; i < targets.length; i++) {
      entry = to(uow, entry, targets[i], i == targets.length - 1 ? detail : null);
    }
    return entry;
  }

  /** Liest {@code key=value} aus einer Detailnotiz der Form {@code a=1;b=2}. */
  public static Long numberFromDetail(String detail, String key) {
    if (detail == null) {
      return null;
    }
    for (String part : detail.split(";")) {
      String prefix = key + "=";
      if (part.startsWith(prefix)) {
        try {
          return Long.parseLong(part.substring(prefix.length()));
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }
}
