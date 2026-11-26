package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMigrationTest {

  @Test
  void migrationIsIdempotentAndWritable(@TempDir Path dir) throws SQLException {
    try (var ds = DataSourceFactory.sqlite(dir.resolve("test.db"))) {
      Migrations.apply(ds);
      Migrations.apply(ds);
      try (var c = ds.getConnection();
          var st = c.prepareStatement("INSERT INTO orders(id, owner, created_at) VALUES (?,?,?)")) {
        st.setLong(1, 1L);
        st.setString(2, "00000000-0000-0000-0000-000000000001");
        st.setLong(3, 0L);
        assertThat(st.executeUpdate()).isEqualTo(1);
      }
    }
  }
}
