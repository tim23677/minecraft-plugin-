package dev.orderhub.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;

/** Erzeugt HikariCP-Pools fuer SQLite und MariaDB/MySQL. */
public final class DataSourceFactory {

  /** Wartezeit auf eine gesperrte SQLite-Datei, bevor SQLITE_BUSY gemeldet wird. */
  public static final int SQLITE_BUSY_TIMEOUT_MILLIS = 5_000;

  private DataSourceFactory() {}

  public static HikariDataSource sqlite(Path file) {
    return sqlite(file, 1, SQLITE_BUSY_TIMEOUT_MILLIS);
  }

  /**
   * SQLite mit WAL, busy_timeout und aktiven Fremdschluesseln. {@code poolSize = 1} ist fuer eine
   * einzelne Instanz richtig; mehrere Prozesse (oder Tests) teilen sich die Datei ueber
   * busy_timeout.
   */
  public static HikariDataSource sqlite(Path file, int poolSize, int busyTimeoutMillis) {
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
    config.addDataSourceProperty("journal_mode", "WAL");
    config.addDataSourceProperty("synchronous", "NORMAL");
    config.addDataSourceProperty("busy_timeout", Integer.toString(busyTimeoutMillis));
    config.addDataSourceProperty("foreign_keys", "true");
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(poolSize);
    config.setPoolName("orderhub-sqlite");
    return new HikariDataSource(config);
  }

  public static HikariDataSource mariaDb(String jdbcUrl, String user, String password) {
    return mariaDb(jdbcUrl, user, password, 10);
  }

  public static HikariDataSource mariaDb(
      String jdbcUrl, String user, String password, int maxPoolSize) {
    var config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(maxPoolSize);
    config.setMinimumIdle(Math.min(2, maxPoolSize));
    config.setConnectionTimeout(10_000);
    config.setValidationTimeout(5_000);
    // Unter wait_timeout des Servers bleiben, sonst liefert der Pool tote Verbindungen.
    config.setMaxLifetime(25 * 60_000L);
    config.setKeepaliveTime(2 * 60_000L);
    config.setPoolName("orderhub-mariadb");
    return new HikariDataSource(config);
  }
}
