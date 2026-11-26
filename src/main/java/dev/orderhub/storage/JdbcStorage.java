package dev.orderhub.storage;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * JDBC-Implementierung von {@link Storage}. Arbeit laeuft auf einem begrenzten Executor mit
 * benannten Daemon-Threads ({@code orderhub-db-N}). Jede Transaktion: SQLite {@code BEGIN
 * IMMEDIATE}, MariaDB {@code REPEATABLE READ}. Bei Deadlock, Lock-Timeout oder SQLITE_BUSY wird mit
 * exponentiellem Backoff wiederholt, {@code work} darf deshalb ausserhalb der Datenbank keine
 * Nebenwirkungen haben.
 */
public final class JdbcStorage implements Storage {

  private final DataSource dataSource;
  private final Dialect dialect;
  private final StorageConfig config;
  private final LongSupplier clock;
  private final ThreadPoolExecutor executor;

  /** Uebernimmt den Pool: {@link #close()} schliesst ihn, falls er {@link AutoCloseable} ist. */
  public JdbcStorage(DataSource dataSource, Dialect dialect, StorageConfig config) {
    this(dataSource, dialect, config, System::currentTimeMillis);
  }

  public JdbcStorage(
      DataSource dataSource, Dialect dialect, StorageConfig config, LongSupplier clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.executor =
        new ThreadPoolExecutor(
            config.dbThreads(),
            config.dbThreads(),
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(config.queueCapacity()),
            new NamedThreads(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /** Migriert die Datenbank und oeffnet dann den Storage. */
  public static JdbcStorage open(DataSource dataSource, Dialect dialect, StorageConfig config)
      throws SQLException {
    Migrations.apply(dataSource, dialect);
    return new JdbcStorage(dataSource, dialect, config);
  }

  @Override
  public <T> CompletableFuture<T> transact(Function<UnitOfWork, T> work) {
    Objects.requireNonNull(work, "work");
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      executor.execute(() -> run(work, future));
    } catch (RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  @Override
  public Dialect dialect() {
    return dialect;
  }

  @Override
  public String serverId() {
    return config.serverId();
  }

  private <T> void run(Function<UnitOfWork, T> work, CompletableFuture<T> future) {
    int attempt = 1;
    while (true) {
      try {
        future.complete(runOnce(work));
        return;
      } catch (Throwable t) {
        if (attempt < config.maxAttempts() && isRetryable(t)) {
          try {
            Thread.sleep(backoff(attempt));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            t.addSuppressed(ie);
            future.completeExceptionally(t);
            return;
          }
          attempt++;
        } else {
          future.completeExceptionally(t);
          return;
        }
      }
    }
  }

  long backoff(int attempt) {
    long exp = config.baseBackoffMillis() << Math.min(attempt - 1, 20);
    long capped = Math.min(config.maxBackoffMillis(), exp);
    if (capped <= 1) {
      return capped;
    }
    // Jitter: 50..100 % der Wartezeit, damit konkurrierende Transaktionen nicht im Gleichtakt laufen.
    return capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
  }

  private <T> T runOnce(Function<UnitOfWork, T> work) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      begin(c);
      try {
        T result = work.apply(new JdbcUnitOfWork(c, dialect, clock.getAsLong()));
        commit(c);
        return result;
      } catch (Throwable t) {
        try {
          rollback(c);
        } catch (SQLException re) {
          t.addSuppressed(re);
        }
        throw t;
      }
    }
  }

  private void begin(Connection c) throws SQLException {
    if (dialect == Dialect.SQLITE) {
      c.setAutoCommit(true);
      try (Statement s = c.createStatement()) {
        s.execute("BEGIN IMMEDIATE");
      }
    } else {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    }
  }

  private void commit(Connection c) throws SQLException {
    if (dialect == Dialect.SQLITE) {
      try (Statement s = c.createStatement()) {
        s.execute("COMMIT");
      }
    } else {
      c.commit();
    }
  }

  private void rollback(Connection c) throws SQLException {
    if (dialect == Dialect.SQLITE) {
      try (Statement s = c.createStatement()) {
        s.execute("ROLLBACK");
      }
    } else {
      c.rollback();
    }
  }

  /** Deadlock, Lock-Timeout, Serialisierungsfehler oder gesperrte SQLite-Datei. */
  static boolean isRetryable(Throwable t) {
    int depth = 0;
    for (Throwable cur = t; cur != null && depth < 10; cur = cur.getCause(), depth++) {
      if (cur instanceof SQLException e && isTransient(e)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTransient(SQLException e) {
    String state = e.getSQLState();
    int code = e.getErrorCode();
    if (state != null && state.startsWith("40")) {
      return true; // 40001 Serialisierungsfehler/Deadlock, 40P01 u. a.
    }
    if (code == 1213 || code == 1205) {
      return true; // MariaDB: Deadlock, Lock wait timeout
    }
    String msg = e.getMessage();
    boolean sqliteMessage =
        msg != null
            && (msg.contains("SQLITE_BUSY")
                || msg.contains("SQLITE_LOCKED")
                || msg.contains("database is locked")
                || msg.contains("database table is locked"));
    int base = code & 0xFF;
    return sqliteMessage || (state == null && (base == 5 || base == 6) && code < 1000);
  }

  @Override
  @SuppressFBWarnings(
      value = "REC_CATCH_EXCEPTION",
      justification = "Der Pool wird ueber AutoCloseable generisch geschlossen")
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(config.closeTimeoutSeconds(), TimeUnit.SECONDS)) {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      if (dataSource instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception e) {
          throw new StorageException("closing pool failed", asSql(e));
        }
      }
    }
  }

  private static SQLException asSql(Exception e) {
    return e instanceof SQLException s ? s : new SQLException(e);
  }

  private static final class NamedThreads implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "orderhub-db-" + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    }
  }
}
