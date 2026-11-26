package dev.orderhub.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MigrationsTest {

  @Test
  void manifestMatchesFilesOnDisk() throws IOException {
    Path root = Path.of("src/main/resources/db/migration");
    List<String> onDisk;
    try (Stream<Path> walk = Files.walk(root)) {
      onDisk =
          walk.filter(p -> p.toString().endsWith(".sql"))
              .map(p -> root.relativize(p).toString().replace('\\', '/'))
              .sorted()
              .collect(Collectors.toList());
    }
    List<String> listed =
        Migrations.read(Migrations.MANIFEST).lines().map(String::trim)
            .filter(l -> !l.isEmpty() && !l.startsWith("#")).sorted().collect(Collectors.toList());
    assertThat(listed).isEqualTo(onDisk);
  }

  @Test
  void loadsCommonBeforeDialectPerVersion() {
    assertThat(Migrations.load(Dialect.SQLITE)).extracting(Migrations.Script::scope)
        .containsExactly("common", "sqlite");
    assertThat(Migrations.load(Dialect.MARIADB)).extracting(Migrations.Script::scope)
        .containsExactly("common", "mariadb");
  }

  @Test
  void substitutesPlaceholdersPerDialect() {
    String sql = "id ${AUTO_ID}, b ${BLOB}, ${TABLE_OPTS}";
    assertThat(Migrations.substitute(sql, Dialect.SQLITE))
        .isEqualTo("id INTEGER PRIMARY KEY AUTOINCREMENT, b BLOB, ");
    assertThat(Migrations.substitute(sql, Dialect.MARIADB))
        .startsWith("id BIGINT PRIMARY KEY AUTO_INCREMENT, b LONGBLOB, ENGINE=InnoDB");
  }

  @Test
  void splitsOnSeparatorAndDropsCommentOnlyParts() {
    String sql = "-- header\nSELECT 1;\n-- @@\n-- nur Kommentar\n-- @@\nCREATE TRIGGER t\nBEGIN\n  SELECT 2;\nEND\n";
    assertThat(Migrations.split(sql)).hasSize(2);
    assertThat(Migrations.split(sql).get(1)).contains("SELECT 2;").endsWith("END");
  }

  @Test
  void checksumIsStableSha256() {
    assertThat(Migrations.sha256("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void retryClassification() {
    assertThat(JdbcStorage.isRetryable(new StorageException(new java.sql.SQLException("x", "40001", 1213)))).isTrue();
    assertThat(JdbcStorage.isRetryable(new java.sql.SQLException("[SQLITE_BUSY] locked", null, 5))).isTrue();
    assertThat(JdbcStorage.isRetryable(new java.sql.SQLException("dup", "23000", 1062))).isFalse();
    assertThat(JdbcStorage.isRetryable(new IllegalStateException("x"))).isFalse();
  }
}
