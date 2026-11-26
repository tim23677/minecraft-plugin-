package dev.orderhub.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;

/** Erzeugt HikariCP-Pools fuer SQLite und MariaDB/MySQL. */
public final class DataSourceFactory {

  private DataSourceFactory() {}

  public static HikariDataSource sqlite(Path file) {
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
    // SQLite vertraegt nur einen Schreiber gleichzeitig.
    config.setMaximumPoolSize(1);
    config.setPoolName("orderhub-sqlite");
    return new HikariDataSource(config);
  }

  public static HikariDataSource mariaDb(String jdbcUrl, String user, String password) {
    var config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(10);
    config.setPoolName("orderhub-mariadb");
    return new HikariDataSource(config);
  }
}
