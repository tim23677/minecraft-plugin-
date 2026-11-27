package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die SQLite-Pragmas kommen ueber Hikari-Verbindungseigenschaften und gelten fuer jede Verbindung.
 */
class DataSourceFactoryTest {

  @TempDir Path dir;

  private static String pragma(Connection c, String name) throws SQLException {
    try (var st = c.createStatement();
        var rs = st.executeQuery("PRAGMA " + name)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  @Test
  void everyFreshConnectionHasWalBusyTimeoutAndForeignKeys() throws Exception {
    try (HikariDataSource ds =
        DataSourceFactory.sqlite(
            dir.resolve("a.db"), 2, DataSourceFactory.SQLITE_BUSY_TIMEOUT_MILLIS)) {
      // Zwei gleichzeitig geoeffnete Verbindungen sind zwei verschiedene, frisch aufgebaute.
      try (Connection first = ds.getConnection();
          Connection second = ds.getConnection()) {
        assertThat(first.unwrap(Connection.class)).isNotSameAs(second.unwrap(Connection.class));
        for (Connection c : new Connection[] {first, second}) {
          assertThat(pragma(c, "journal_mode")).isEqualToIgnoringCase("wal");
          assertThat(pragma(c, "busy_timeout"))
              .isEqualTo(Integer.toString(DataSourceFactory.SQLITE_BUSY_TIMEOUT_MILLIS));
          assertThat(pragma(c, "foreign_keys")).isEqualTo("1");
        }
      }
    }
  }

  @Test
  void theBusyTimeoutFollowsTheConfiguredValue() throws Exception {
    try (HikariDataSource ds = DataSourceFactory.sqlite(dir.resolve("b.db"), 1, 1234);
        Connection c = ds.getConnection()) {
      assertThat(pragma(c, "busy_timeout")).isEqualTo("1234");
    }
  }

  @Test
  void foreignKeysAreEnforcedNotJustReported() throws Exception {
    try (HikariDataSource ds = DataSourceFactory.sqlite(dir.resolve("c.db"));
        Connection c = ds.getConnection();
        var st = c.createStatement()) {
      st.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
      st.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, p INTEGER REFERENCES parent(id))");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> st.execute("INSERT INTO child(id, p) VALUES (1, 999)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("FOREIGN KEY");
    }
  }

  @Test
  void driversAreNamedExplicitlyAndLoadable() throws ClassNotFoundException {
    // Das Shadow-Jar behaelt nur einen META-INF/services/java.sql.Driver-Eintrag. Ohne
    // ausdrueckliche Klasse fehlt dem MariaDB-Treiber im ausgelieferten Jar die Registrierung.
    var sqlite = DataSourceFactory.sqliteConfig(java.nio.file.Path.of("x.db"), 1, 1000);
    var maria = DataSourceFactory.mariaDbConfig("jdbc:mariadb://localhost/x", "u", "p", 2);
    assertThat(sqlite.getDriverClassName()).isEqualTo("org.sqlite.JDBC");
    assertThat(maria.getDriverClassName()).isEqualTo("org.mariadb.jdbc.Driver");
    assertThat(Class.forName(sqlite.getDriverClassName())).isNotNull();
    assertThat(Class.forName(maria.getDriverClassName())).isNotNull();
  }
}
