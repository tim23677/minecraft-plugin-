package dev.orderhub.tx;

import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Blockierender Zugriff auf {@link Storage#transact}. Darf nur von Worker-Threads aufgerufen
 * werden, nie vom Server-, Region- oder Entity-Thread. {@link Error} (z. B. ein simulierter
 * Prozessabbruch) wird unveraendert weitergereicht und nie abgefangen.
 */
public final class TxRunner {

  private TxRunner() {}

  /** Fuehrt {@code work} in einer Transaktion aus und wartet auf das Ergebnis. */
  public static <T> T run(Storage storage, Function<UnitOfWork, T> work) {
    try {
      return storage.transact(work).join();
    } catch (CompletionException | CancellationException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new StorageFailureException(cause);
    }
  }

  /** Nicht behandelbarer Fehler der Persistenzschicht. */
  public static final class StorageFailureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageFailureException(Throwable cause) {
      super(cause);
    }
  }
}
