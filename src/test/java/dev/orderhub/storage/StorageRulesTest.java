package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regeln der Persistenzschicht ohne echte Abfragen: welche Fehler wiederholt werden, was ein
 * Duplikat ist, wie lange gewartet wird und welche Konfigurationen erlaubt sind. Ein falsches
 * Wiederholen kann Geld doppelt buchen, ein ausbleibendes Wiederholen verliert Lieferungen.
 */
class StorageRulesTest {

  @TempDir Path dir;

  // ---- Wiederholbare Fehler ----

  @Test
  void deadlockAndLockTimeoutAreRetriedByCodeOrByStatePrefix() {
    assertThat(JdbcStorage.isRetryable(new SQLException("d", "HY000", 1213))).isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("t", "HY000", 1205))).isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("s", "40001", 0))).isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("s", "40P01", 0))).isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("s", "41000", 0))).isFalse();
    assertThat(JdbcStorage.isRetryable(new SQLException("s", "23000", 1062))).isFalse();
  }

  @Test
  void sqliteBusyAndLockedAreRetriedByMessageOrByPrimaryResultCode() {
    assertThat(JdbcStorage.isRetryable(new SQLException("[SQLITE_LOCKED] x", null, 0))).isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("the database is locked", null, 0)))
        .isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("database table is locked", null, 0)))
        .isTrue();
    // ohne Text nur ueber den Basiscode 5 (BUSY) oder 6 (LOCKED), auch erweiterte Codes
    assertThat(JdbcStorage.isRetryable(new SQLException("x", null, 5))).isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("x", null, 6))).isTrue();
    assertThat(JdbcStorage.isRetryable(new SQLException("x", null, 261))).isTrue(); // 5 | 1 << 8
    assertThat(JdbcStorage.isRetryable(new SQLException("x", null, 7))).isFalse();
    assertThat(JdbcStorage.isRetryable(new SQLException("x", null, 4))).isFalse();
    // MariaDB-Codes im Bereich ab 1000 sind keine SQLite-Codes, auch wenn das untere Byte 5 ist
    assertThat(JdbcStorage.isRetryable(new SQLException("x", null, 1029))).isFalse();
    // mit SQLState ist der Basiscode kein Kriterium
    assertThat(JdbcStorage.isRetryable(new SQLException("x", "HY000", 5))).isFalse();
  }

  @Test
  void theCauseChainIsSearchedButOnlyToALimitedDepth() {
    Throwable deadlock = new SQLException("d", "40001", 1213);
    Throwable chain = deadlock;
    for (int i = 0; i < 8; i++) {
      chain = new RuntimeException("wrap " + i, chain);
    }
    assertThat(JdbcStorage.isRetryable(chain)).isTrue();
    Throwable tooDeep = deadlock;
    for (int i = 0; i < 12; i++) {
      tooDeep = new RuntimeException("wrap " + i, tooDeep);
    }
    assertThat(JdbcStorage.isRetryable(tooDeep)).isFalse();
    assertThat(JdbcStorage.isRetryable(new RuntimeException("plain"))).isFalse();
  }

  // ---- Duplikate ----

  @Test
  void uniqueViolationsAreRecognisedForBothDatabases() {
    assertThat(Jdbc.isUniqueViolation(new SQLException("Duplicate entry", "23000", 1062))).isTrue();
    assertThat(
            Jdbc.isUniqueViolation(
                new SQLException("[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: t.c")))
        .isTrue();
    assertThat(
            Jdbc.isUniqueViolation(
                new SQLException("[SQLITE_CONSTRAINT_PRIMARYKEY] PRIMARY KEY must be unique")))
        .isTrue();
    assertThat(Jdbc.isUniqueViolation(new SQLException("NOT NULL constraint failed", "23000", 19)))
        .isFalse();
    assertThat(Jdbc.isUniqueViolation(new SQLException((String) null))).isFalse();
  }

  // ---- Wartezeit ----

  private JdbcStorage storage(StorageConfig config) {
    var ds = DataSourceFactory.sqlite(dir.resolve("backoff.db"), 1, 1_000);
    return new JdbcStorage(ds, Dialect.SQLITE, config);
  }

  @Test
  void backoffDoublesPerAttemptWithJitterAndNeverExceedsTheCap() {
    JdbcStorage s = storage(StorageConfig.defaults("s").withBackoff(100, 1_000));
    try {
      for (int i = 0; i < 100; i++) {
        assertThat(s.backoff(1)).isBetween(50L, 100L);
        assertThat(s.backoff(2)).isBetween(100L, 200L);
        assertThat(s.backoff(3)).isBetween(200L, 400L);
        assertThat(s.backoff(5)).isBetween(500L, 1_000L); // 1600 -> gedeckelt auf 1000
        assertThat(s.backoff(500)).isBetween(500L, 1_000L); // Verschiebung ist begrenzt
      }
    } finally {
      s.close();
    }
  }

  @Test
  void aZeroOrOneMillisecondBackoffIsNotJittered() {
    JdbcStorage zero = storage(StorageConfig.defaults("s").withBackoff(0, 0));
    try {
      assertThat(zero.backoff(1)).isZero();
      assertThat(zero.backoff(30)).isZero();
    } finally {
      zero.close();
    }
    JdbcStorage one = storage(StorageConfig.defaults("s").withBackoff(1, 1));
    try {
      assertThat(one.backoff(1)).isEqualTo(1L);
      assertThat(one.backoff(9)).isEqualTo(1L);
    } finally {
      one.close();
    }
  }

  // ---- Konfiguration ----

  @Test
  void configRejectsNonsenseAndAcceptsTheEdges() {
    assertThatThrownBy(() -> new StorageConfig("s", 0, 1, 1, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageConfig("s", 1, 0, 1, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageConfig("s", 1, 1, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageConfig("s", 1, 1, 1, -1, 5, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageConfig("s", 1, 1, 1, 6, 5, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageConfig("s", 1, 1, 1, 0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StorageConfig(null, 1, 1, 1, 0, 0, 0))
        .isInstanceOf(NullPointerException.class);
    StorageConfig edge = new StorageConfig("s", 1, 1, 1, 5, 5, 0);
    assertThat(edge.maxAttempts()).isEqualTo(1);
  }

  @Test
  void configCopiesChangeExactlyOneValue() {
    StorageConfig base = new StorageConfig("srv", 2, 3, 4, 5, 6, 7);
    assertThat(base.withMaxAttempts(9)).isEqualTo(new StorageConfig("srv", 2, 3, 9, 5, 6, 7));
    assertThat(base.withBackoff(10, 20)).isEqualTo(new StorageConfig("srv", 2, 3, 4, 10, 20, 7));
    assertThat(base.withDbThreads(8)).isEqualTo(new StorageConfig("srv", 8, 3, 4, 5, 6, 7));
    assertThat(base.withQueueCapacity(11)).isEqualTo(new StorageConfig("srv", 2, 11, 4, 5, 6, 7));
  }

  @Test
  void onlyMariaDbSupportsRowLocks() {
    assertThat(Dialect.MARIADB.supportsRowLocks()).isTrue();
    assertThat(Dialect.SQLITE.supportsRowLocks()).isFalse();
  }
}
