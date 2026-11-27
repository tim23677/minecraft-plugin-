package dev.orderhub.storage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Einstieg in die Persistenz. Jede Arbeit laeuft als Transaktion auf einem begrenzten DB-Executor,
 * niemals auf Server-, Region- oder Entity-Threads.
 */
public interface Storage extends AutoCloseable {

  /**
   * Fuehrt {@code work} in einer Transaktion aus. Bei Deadlock oder Serialisierungsfehler wird mit
   * begrenztem Backoff wiederholt, {@code work} muss deshalb frei von Nebenwirkungen ausserhalb der
   * Datenbank sein. Eine Ausnahme rollt zurueck und bricht das Future exceptionally ab.
   */
  <T> CompletableFuture<T> transact(Function<UnitOfWork, T> work);

  /** Auslastung von Verbindungspool und Datenbank-Executor, fuer {@code /orderadmin health}. */
  record Health(
      int poolActive, int poolIdle, int poolWaiting, int poolTotal, int queued, int running) {}

  /** Momentaufnahme der Auslastung. Standard: leer (z. B. In-Memory-Test-Storage). */
  default Health health() {
    return new Health(0, 0, 0, 0, 0, 0);
  }

  /** Dialekt der verbundenen Datenbank. */
  Dialect dialect();

  /** Server-ID dieser Instanz. */
  String serverId();

  @Override
  void close();
}
