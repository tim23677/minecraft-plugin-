package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseSetupTest {

  @TempDir Path dir;

  private static YamlConfiguration storage(String yaml) {
    YamlConfiguration cfg = new YamlConfiguration();
    try {
      cfg.loadFromString(yaml);
    } catch (org.bukkit.configuration.InvalidConfigurationException e) {
      throw new IllegalArgumentException(e);
    }
    return cfg;
  }

  @Test
  void opensSqliteInThePluginFolderAndMigrates() throws Exception {
    try (JdbcStorage s =
        DatabaseSetup.open(storage("type: sqlite\nsqlite:\n  file: t.db\n"), dir, "main")) {
      assertThat(s.dialect()).isEqualTo(Dialect.SQLITE);
      assertThat(dir.resolve("t.db")).exists();
      try (var c = s.dataSource().getConnection();
          var st = c.createStatement();
          var rs = st.executeQuery("SELECT name FROM sqlite_master WHERE name = 'orders'")) {
        assertThat(rs.next()).isTrue();
      }
    }
  }

  @Test
  void rejectsFileNamesWithPathParts() {
    for (String bad : new String[] {"../x.db", "a/b.db", "a\\b.db", ""}) {
      YamlConfiguration cfg = new YamlConfiguration();
      cfg.set("type", "sqlite");
      cfg.set("sqlite.file", bad);
      assertThatThrownBy(() -> DatabaseSetup.open(cfg, dir, "main"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("storage.sqlite.file");
    }
  }

  @Test
  void unknownTypeAndMissingSectionAreClearErrors() {
    assertThatThrownBy(() -> DatabaseSetup.open(storage("type: oracle\n"), dir, "main"))
        .hasMessageContaining("sqlite' or 'mariadb");
    assertThatThrownBy(() -> DatabaseSetup.open(null, dir, "main")).hasMessageContaining("storage");
  }

  @Test
  void passwordComesOnlyFromTheNamedEnvironmentVariable() {
    String yaml =
        "type: mariadb\nmariadb:\n  jdbc-url: 'jdbc:mariadb://127.0.0.1:1/x?sslMode=disable'\n"
            + "  user: u\n  password-env: MY_DB_PASSWORD\n";
    assertThatThrownBy(() -> DatabaseSetup.open(storage(yaml), dir, "main", name -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MY_DB_PASSWORD")
        .hasMessageContaining("not set");
    // Variable vorhanden: der Fehler kommt jetzt erst vom (nicht erreichbaren) Server.
    Map<String, String> env = Map.of("MY_DB_PASSWORD", "secret");
    assertThatThrownBy(() -> DatabaseSetup.open(storage(yaml), dir, "main", env::get))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("not set")
        .hasMessageNotContaining("secret");
  }

  @Test
  void mariadbUrlMustBeAMariadbUrl() {
    String yaml = "type: mariadb\nmariadb:\n  jdbc-url: 'jdbc:mysql://x/y'\n";
    assertThatThrownBy(() -> DatabaseSetup.open(storage(yaml), dir, "main"))
        .hasMessageContaining("jdbc:mariadb:");
  }
}
