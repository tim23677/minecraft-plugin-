package dev.orderhub.storage;

import java.util.Objects;

/**
 * Konfiguration der JDBC-Storage-Schicht.
 *
 * @param serverId ID dieser Instanz
 * @param dbThreads Groesse des DB-Executors
 * @param queueCapacity Warteschlange des DB-Executors, danach wird abgelehnt
 * @param maxAttempts Versuche je Transaktion inklusive des ersten (Standard 5)
 * @param baseBackoffMillis Wartezeit vor dem ersten Wiederholen, verdoppelt sich je Versuch
 * @param maxBackoffMillis Obergrenze der Wartezeit
 * @param closeTimeoutSeconds Wartezeit auf laufende Transaktionen bei close()
 */
public record StorageConfig(
    String serverId,
    int dbThreads,
    int queueCapacity,
    int maxAttempts,
    long baseBackoffMillis,
    long maxBackoffMillis,
    int closeTimeoutSeconds) {

  public StorageConfig {
    Objects.requireNonNull(serverId, "serverId");
    if (dbThreads < 1 || queueCapacity < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("threads, queue and attempts must be >= 1");
    }
    if (baseBackoffMillis < 0 || maxBackoffMillis < baseBackoffMillis) {
      throw new IllegalArgumentException("invalid backoff");
    }
    if (closeTimeoutSeconds < 0) {
      throw new IllegalArgumentException("closeTimeoutSeconds");
    }
  }

  public static StorageConfig defaults(String serverId) {
    return new StorageConfig(serverId, 4, 1024, 5, 25L, 1_000L, 15);
  }

  public StorageConfig withMaxAttempts(int attempts) {
    return new StorageConfig(
        serverId,
        dbThreads,
        queueCapacity,
        attempts,
        baseBackoffMillis,
        maxBackoffMillis,
        closeTimeoutSeconds);
  }

  public StorageConfig withBackoff(long baseMillis, long maxMillis) {
    return new StorageConfig(
        serverId,
        dbThreads,
        queueCapacity,
        maxAttempts,
        baseMillis,
        maxMillis,
        closeTimeoutSeconds);
  }

  public StorageConfig withDbThreads(int threads) {
    return new StorageConfig(
        serverId,
        threads,
        queueCapacity,
        maxAttempts,
        baseBackoffMillis,
        maxBackoffMillis,
        closeTimeoutSeconds);
  }

  public StorageConfig withQueueCapacity(int capacity) {
    return new StorageConfig(
        serverId,
        dbThreads,
        capacity,
        maxAttempts,
        baseBackoffMillis,
        maxBackoffMillis,
        closeTimeoutSeconds);
  }
}
