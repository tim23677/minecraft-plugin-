package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransactionLogFileTest {

  @Test
  void linesAreWrittenOnePerLineToTheCurrentFile(@TempDir Path dir) throws Exception {
    try (TransactionLogFile file = new TransactionLogFile(dir, 1_000_000, 3)) {
      file.sink().write("event=A tx=1");
      file.sink().write("event=B tx=2");
    }
    assertThat(Files.readAllLines(dir.resolve("transactions.0.log")))
        .containsExactly("event=A tx=1", "event=B tx=2");
  }

  @Test
  void theFileRotatesAndKeepsOnlyTheConfiguredNumberOfFiles(@TempDir Path dir) throws Exception {
    String line = "x".repeat(500);
    try (TransactionLogFile file = new TransactionLogFile(dir, 2048, 3)) {
      for (int i = 0; i < 200; i++) {
        file.sink().write(line);
      }
    }
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(
              files.filter(
                  f -> String.valueOf(f.getFileName()).matches("transactions\\.\\d+\\.log")))
          .hasSizeLessThanOrEqualTo(3)
          .allSatisfy(f -> assertThat(Files.size(f)).isLessThanOrEqualTo(2048 + 600));
    }
  }

  @Test
  void aSecondStartAppendsInsteadOfOverwriting(@TempDir Path dir) throws Exception {
    try (TransactionLogFile file = new TransactionLogFile(dir, 1_000_000, 2)) {
      file.sink().write("first");
    }
    try (TransactionLogFile file = new TransactionLogFile(dir, 1_000_000, 2)) {
      file.sink().write("second");
    }
    assertThat(String.join(",", Files.readAllLines(dir.resolve("transactions.0.log"))))
        .contains("first")
        .contains("second");
  }

  @Test
  void nonsenseLimitsAreRejected(@TempDir Path dir) {
    assertThatThrownBy(() -> new TransactionLogFile(dir, 10, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TransactionLogFile(dir, 4096, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
