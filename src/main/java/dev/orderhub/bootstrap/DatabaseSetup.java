package dev.orderhub.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.nio.file.Path;
import java.sql.SQLException;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Baut aus {@code database.yml} den Pool und {@link JdbcStorage}. Das Passwort kommt bevorzugt aus
 * der Umgebung.
 */
public final class DatabaseSetup {

  private DatabaseSetup() {}

  /**
   * Wirft {@link IllegalStateException} mit verstaendlicher Meldung, wenn die Datenbank nicht
   * startet.
   */
  public static JdbcStorage open(ConfigurationSection db, Path dataDir, String serverId) {
    String type = java.util.Objects.requireNonNullElse(db.getString("type"), "sqlite");
    ConfigurationSection retry = db.getConfigurationSection("retry");
    StorageConfig defaults = StorageConfig.defaults(serverId);
    StorageConfig config =
        new StorageConfig(
            serverId,
            defaults.dbThreads(),
            defaults.queueCapacity(),
            retry == null ? defaults.maxAttempts() : retry.getInt("max-attempts", 5),
            retry == null ? defaults.baseBackoffMillis() : retry.getInt("initial-backoff-ms", 25),
            retry == null ? defaults.maxBackoffMillis() : retry.getInt("max-backoff-ms", 500),
            defaults.closeTimeoutSeconds());
    HikariDataSource pool = null;
    try {
      if ("mariadb".equalsIgnoreCase(type)) {
        ConfigurationSection m = db.getConfigurationSection("mariadb");
        if (m == null) {
          throw new IllegalStateException("database.yml: section 'mariadb' missing");
        }
        String password = System.getenv("ORDERHUB_DB_PASSWORD");
        if (password == null || password.isEmpty()) {
          password = m.getString("password", "");
        }
        String url =
            "jdbc:mariadb://"
                + m.getString("host", "localhost")
                + ":"
                + m.getInt("port", 3306)
                + "/"
                + m.getString("database", "orderhub")
                + (m.getBoolean("use-ssl", false) ? "?sslMode=trust" : "?sslMode=disable");
        pool =
            DataSourceFactory.mariaDb(
                url, m.getString("username", "orderhub"), password, m.getInt("pool-size", 10));
        return JdbcStorage.open(pool, Dialect.MARIADB, config);
      }
      String file =
          java.util.Objects.requireNonNullElse(db.getString("sqlite.file"), "orderhub.db");
      if (file.contains("/") || file.contains("\\") || file.contains("..")) {
        throw new IllegalStateException("database.yml: sqlite.file must be a plain file name");
      }
      pool = DataSourceFactory.sqlite(dataDir.resolve(file));
      return JdbcStorage.open(pool, Dialect.SQLITE, config);
    } catch (SQLException | RuntimeException e) {
      if (pool != null) {
        pool.close();
      }
      throw new IllegalStateException("database could not be opened: " + e.getMessage(), e);
    }
  }
}
