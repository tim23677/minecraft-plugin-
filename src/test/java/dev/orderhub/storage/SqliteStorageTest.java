package dev.orderhub.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Storage-Vertrag gegen eine echte SQLite-Datei (zwei Pools auf derselben Datei = zwei Server). */
class SqliteStorageTest extends StorageContractTest {

  private Path dir;
  private Path file;
  private final List<AutoCloseable> pools = new ArrayList<>();
  private DataSource raw;

  @BeforeEach
  void createDirectory() throws IOException {
    dir = Files.createTempDirectory("orderhub-sqlite");
    file = dir.resolve("orderhub.db");
  }

  @AfterEach
  void cleanUp() throws Exception {
    for (AutoCloseable c : pools) {
      c.close();
    }
    pools.clear();
    raw = null;
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @Override
  JdbcStorage open(String serverId, StorageConfig config) throws Exception {
    var ds = DataSourceFactory.sqlite(file, 2, 15_000);
    Migrations.apply(ds, Dialect.SQLITE);
    return new JdbcStorage(ds, Dialect.SQLITE, config);
  }

  @Override
  Dialect dialect() {
    return Dialect.SQLITE;
  }

  @Override
  DataSource rawDataSource() {
    if (raw == null) {
      var ds = DataSourceFactory.sqlite(file, 2, 15_000);
      pools.add(ds);
      raw = ds;
    }
    return raw;
  }

  @Override
  void resetDatabase() throws Exception {
    try (var walk = Files.walk(dir)) {
      walk.filter(p -> !p.equals(dir)).forEach(p -> p.toFile().delete());
    }
  }

  @Test
  void walModeAndForeignKeysAreActive() throws Exception {
    storage();
    try (var c = rawDataSource().getConnection();
        var st = c.createStatement()) {
      try (var rs = st.executeQuery("PRAGMA journal_mode")) {
        rs.next();
        org.assertj.core.api.Assertions.assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
      }
      try (var rs = st.executeQuery("PRAGMA foreign_keys")) {
        rs.next();
        org.assertj.core.api.Assertions.assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }
}
