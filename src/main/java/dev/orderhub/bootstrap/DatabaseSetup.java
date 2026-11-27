package dev.orderhub.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import dev.orderhub.storage.Backups;
import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Baut aus dem Abschnitt {@code storage} der {@code config.yml} den Pool und {@link JdbcStorage}
 * und fuehrt die Migrationen aus. Blockiert (Dateizugriff, Netzwerk, DDL) und darf deshalb nie auf
 * einem Server- oder Region-Thread laufen, sondern nur ueber den Async-Scheduler.
 */
public final class DatabaseSetup {

  private static final Logger LOG = Logger.getLogger("OrderHub");

  private DatabaseSetup() {}

  /** Wie {@link #open(ConfigurationSection, Path, String, Function)} mit der Prozessumgebung. */
  public static JdbcStorage open(ConfigurationSection storage, Path dataDir, String serverId) {
    return open(storage, dataDir, serverId, System::getenv);
  }

  /**
   * Oeffnet die Datenbank.
   *
   * @param env Zugriff auf Umgebungsvariablen (fuer Tests austauschbar)
   * @throws IllegalStateException mit verstaendlicher Meldung, wenn die Datenbank nicht startet
   */
  public static JdbcStorage open(
      ConfigurationSection storage, Path dataDir, String serverId, Function<String, String> env) {
    if (storage == null) {
      throw new IllegalStateException("config.yml: section 'storage' is missing");
    }
    String type = text(storage, "type", "sqlite").toLowerCase(Locale.ROOT);
    StorageConfig config = storageConfig(storage, serverId);
    HikariDataSource pool = null;
    try {
      switch (type) {
        case "sqlite" -> {
          String file = text(storage, "sqlite.file", "orderhub.db");
          if (file.isBlank() || file.contains("/") || file.contains("\\") || file.contains("..")) {
            throw new IllegalStateException(
                "config.yml: storage.sqlite.file must be a plain file name, got '" + file + "'");
          }
          pool = DataSourceFactory.sqlite(dataDir.resolve(file));
          // Vor einer Schema-Migration eine Kopie der bestehenden Daten ablegen.
          try {
            Backups.beforeMigration(pool, Dialect.SQLITE, dataDir.resolve("backups"))
                .ifPresent(b -> LOG.info("Database backup before migration: " + b));
          } catch (java.io.IOException e) {
            throw new IllegalStateException(
                "backup before migration failed, refusing to migrate: " + e.getMessage(), e);
          }
          return JdbcStorage.open(pool, Dialect.SQLITE, config);
        }
        case "mariadb" -> {
          ConfigurationSection m = storage.getConfigurationSection("mariadb");
          if (m == null) {
            throw new IllegalStateException("config.yml: section 'storage.mariadb' is missing");
          }
          String url = text(m, "jdbc-url", "");
          if (!url.startsWith("jdbc:mariadb:")) {
            throw new IllegalStateException(
                "config.yml: storage.mariadb.jdbc-url must start with jdbc:mariadb:");
          }
          warnAboutTransport(url);
          String passwordEnv = text(m, "password-env", "");
          String password = "";
          if (!passwordEnv.isBlank()) {
            String value = env.apply(passwordEnv);
            if (value == null) {
              throw new IllegalStateException(
                  "environment variable '"
                      + passwordEnv
                      + "' (storage.mariadb.password-env) is not set");
            }
            password = value;
          }
          pool =
              DataSourceFactory.mariaDb(
                  url,
                  text(m, "user", "orderhub"),
                  password,
                  Math.max(1, m.getInt("pool-size", 10)));
          return JdbcStorage.open(pool, Dialect.MARIADB, config);
        }
        default ->
            throw new IllegalStateException(
                "config.yml: storage.type must be 'sqlite' or 'mariadb', got '" + type + "'");
      }
    } catch (SQLException | RuntimeException e) {
      if (pool != null) {
        pool.close();
      }
      if (e instanceof IllegalStateException ise) {
        throw ise;
      }
      throw new IllegalStateException("database could not be opened: " + e.getMessage(), e);
    }
  }

  private static StorageConfig storageConfig(ConfigurationSection storage, String serverId) {
    StorageConfig defaults = StorageConfig.defaults(serverId);
    ConfigurationSection retry = storage.getConfigurationSection("retry");
    if (retry == null) {
      return defaults;
    }
    return new StorageConfig(
        serverId,
        defaults.dbThreads(),
        defaults.queueCapacity(),
        Math.max(1, retry.getInt("max-attempts", defaults.maxAttempts())),
        Math.max(0, retry.getLong("initial-backoff-ms", defaults.baseBackoffMillis())),
        Math.max(
            Math.max(0, retry.getLong("initial-backoff-ms", defaults.baseBackoffMillis())),
            retry.getLong("max-backoff-ms", defaults.maxBackoffMillis())),
        defaults.closeTimeoutSeconds());
  }

  private static String text(ConfigurationSection section, String path, String def) {
    return Objects.requireNonNullElse(section.getString(path), def);
  }

  /** Warnt vor unverschluesselten oder ungeprueften Verbindungen zu nicht lokalen Servern. */
  static void warnAboutTransport(String url) {
    String lower = url.toLowerCase(Locale.ROOT);
    boolean local =
        lower.contains("//localhost") || lower.contains("//127.0.0.1") || lower.contains("//[::1]");
    if (local) {
      return;
    }
    if (!lower.contains("sslmode=") || lower.contains("sslmode=disable")) {
      LOG.warning(
          "storage.mariadb.jdbc-url points to a non local host without TLS. Credentials and data"
              + " travel unencrypted. Add sslMode=verify-full to the URL.");
    } else if (lower.contains("sslmode=trust")) {
      LOG.warning(
          "storage.mariadb.jdbc-url uses sslMode=trust: traffic is encrypted but the server"
              + " certificate is not verified. Use verify-ca or verify-full.");
    }
  }
}
