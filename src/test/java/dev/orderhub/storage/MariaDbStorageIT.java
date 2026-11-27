package dev.orderhub.storage;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * Storage-Vertrag gegen eine echte MariaDB. Quelle der Datenbank in dieser Reihenfolge:
 *
 * <ol>
 *   <li>Umgebungsvariablen oder System-Properties {@code ORDERHUB_TEST_MARIADB_URL}, {@code
 *       ORDERHUB_TEST_MARIADB_USER}, {@code ORDERHUB_TEST_MARIADB_PASSWORD}
 *   <li>Testcontainers (braucht einen Docker-Daemon)
 *   <li>sonst wird der Test uebersprungen (Assumption), nicht bestanden
 * </ol>
 *
 * <p>Achtung: Die Zieldatenbank wird vor jedem Test geleert (alle Tabellen werden gedroppt).
 */
@Tag("integration")
class MariaDbStorageIT extends StorageContractTest {

  /** Beschreibt, woher die Datenbank stammt, fuer den Testbericht. */
  static String source = "none";

  private static MariaDBContainer container;
  private static String url;
  private static String user;
  private static String password;
  private static boolean resolved;

  private final List<HikariDataSource> pools = new ArrayList<>();
  private DataSource raw;

  private static String setting(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      v = System.getProperty(name);
    }
    return v == null || v.isBlank() ? null : v;
  }

  private static synchronized void resolve() {
    if (resolved) {
      return;
    }
    resolved = true;
    String envUrl = setting("ORDERHUB_TEST_MARIADB_URL");
    if (envUrl != null) {
      url = envUrl;
      user = setting("ORDERHUB_TEST_MARIADB_USER");
      password = setting("ORDERHUB_TEST_MARIADB_PASSWORD");
      source = "environment";
      return;
    }
    try {
      if (DockerClientFactory.instance().isDockerAvailable()) {
        container = new MariaDBContainer("mariadb:11.4");
        container.start();
        url = container.getJdbcUrl();
        user = container.getUsername();
        password = container.getPassword();
        source = "testcontainers";
      }
    } catch (RuntimeException | LinkageError e) {
      url = null;
    }
  }

  @BeforeEach
  void requireDatabase() throws Exception {
    resolve();
    Assumptions.assumeTrue(
        url != null,
        "Keine MariaDB: ORDERHUB_TEST_MARIADB_URL setzen oder Docker fuer Testcontainers starten");
    resetDatabase();
  }

  @AfterEach
  void closePools() {
    for (HikariDataSource p : pools) {
      p.close();
    }
    pools.clear();
    raw = null;
  }

  @AfterAll
  static void stopContainer() {
    if (container != null) {
      container.stop();
    }
  }

  @Override
  Dialect dialect() {
    return Dialect.MARIADB;
  }

  @Override
  JdbcStorage open(String serverId, StorageConfig config) throws Exception {
    HikariDataSource ds = DataSourceFactory.mariaDb(url, user, password, 6);
    Migrations.apply(ds, Dialect.MARIADB);
    return new JdbcStorage(ds, Dialect.MARIADB, config);
  }

  @Override
  DataSource rawDataSource() {
    if (raw == null) {
      HikariDataSource ds = DataSourceFactory.mariaDb(url, user, password, 6);
      pools.add(ds);
      raw = ds;
    }
    return raw;
  }

  @Override
  void resetDatabase() throws Exception {
    try (Connection c = rawDataSource().getConnection();
        Statement st = c.createStatement()) {
      List<String> tables = new ArrayList<>();
      try (var rs =
          st.executeQuery(
              "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
      }
      st.execute("SET FOREIGN_KEY_CHECKS = 0");
      for (String t : tables) {
        if (!t.matches("[A-Za-z0-9_]+")) {
          throw new SQLException("unexpected table name " + t);
        }
        st.execute("DROP TABLE `" + t + "`");
      }
      st.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
  }
}
