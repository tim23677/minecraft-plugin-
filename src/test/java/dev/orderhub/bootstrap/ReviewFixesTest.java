package dev.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the findings of the security review of 2.0.0. */
class ReviewFixesTest {

  @Test
  void dumpHidesCredentialsInJdbcQuery() {
    String out =
        SupportDump.scrub(
            "jdbc:mariadb://db.example.com/orderhub?user=oh&password=geheim&sslMode=verify-full");
    assertThat(out).doesNotContain("geheim").doesNotContain("user=oh").contains("sslMode=verify-full");
  }

  @Test
  void dumpHidesIpv6() {
    assertThat(SupportDump.scrub("client 2001:db8::1 connected")).doesNotContain("2001:db8");
    assertThat(SupportDump.scrub("jdbc:mariadb://[2001:db8::5]:3306/x")).doesNotContain("2001:db8");
  }

  @Test
  void localHostIsParsedNotSearched() {
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://localhost:3306/x")).isTrue();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://127.0.0.1/x")).isTrue();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://[::1]:3306/x")).isTrue();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://db.example.com/x?a=//localhost")).isFalse();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://localhost.evil.com/x")).isFalse();
    assertThat(DatabaseSetup.isLocalHost("jdbc:mariadb://u:p@db.example.com/x")).isFalse();
  }

  @Test
  void logFileDirectoryWithPercentSignStaysWhereItIs(@TempDir Path temp) throws Exception {
    Path dir = temp.resolve("srv%h");
    try (TransactionLogFile file = new TransactionLogFile(dir, 4096, 2)) {
      file.sink().write("line");
    }
    try (var files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .anyMatch(n -> n.startsWith("transactions."));
    }
  }
}
