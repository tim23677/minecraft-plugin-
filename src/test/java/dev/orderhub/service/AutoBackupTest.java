package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.storage.DataSourceFactory;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JdbcStorage;
import dev.orderhub.storage.StorageConfig;
import dev.orderhub.testsupport.InMemoryStorage;
import dev.orderhub.testsupport.MutableClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoBackupTest {

  @Test
  void createsABackupAndKeepsOnlyTheNewestOnes(@TempDir Path dir) throws Exception {
    try (JdbcStorage storage =
        JdbcStorage.open(
            DataSourceFactory.sqlite(dir.resolve("orderhub.db")),
            Dialect.SQLITE,
            StorageConfig.defaults("test"))) {
      for (int i = 0; i < 4; i++) {
        assertThat(AutoBackup.run(storage, dir, 2)).isPresent();
        Thread.sleep(5); // Dateinamen enthalten Millisekunden
      }
      try (Stream<Path> files = Files.list(dir.resolve("backups"))) {
        assertThat(files.filter(f -> String.valueOf(f.getFileName()).startsWith("auto-")))
            .hasSize(2)
            .allSatisfy(f -> assertThat(Files.size(f)).isPositive());
      }
    }
  }

  @Test
  void otherDialectsOrStoragesAreSkippedWithoutError(@TempDir Path dir) throws Exception {
    assertThat(AutoBackup.run(new InMemoryStorage(new MutableClock(0), "x"), dir, 3)).isEmpty();
    assertThat(dir.resolve("backups")).doesNotExist();
  }
}
