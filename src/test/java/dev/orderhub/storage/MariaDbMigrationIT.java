package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/** Braucht einen Docker-Daemon. Ohne Docker wird der Test uebersprungen, nicht bestanden. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class MariaDbMigrationIT {

  @Container static final MariaDBContainer DB = new MariaDBContainer("mariadb:11.4");

  @Test
  void migrationRunsOnRealMariaDb() throws SQLException {
    try (var ds = DataSourceFactory.mariaDb(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
      Migrations.apply(ds);
      try (var c = ds.getConnection();
          var rs = c.getMetaData().getTables(null, null, "orders", null)) {
        assertThat(rs.next()).isTrue();
      }
    }
  }
}
